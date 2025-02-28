// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.exec;

import static com.google.devtools.build.lib.exec.SpawnLogContext.computeDigest;
import static com.google.devtools.build.lib.exec.SpawnLogContext.getSpawnMetricsProto;

import build.bazel.remote.execution.v2.Platform;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.analysis.platform.PlatformUtils;
import com.google.devtools.build.lib.bazel.execlog.StableSort;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.util.io.AsynchronousMessageOutputStream;
import com.google.devtools.build.lib.util.io.MessageOutputStream;
import com.google.devtools.build.lib.util.io.MessageOutputStreamWrapper.BinaryOutputStreamWrapper;
import com.google.devtools.build.lib.util.io.MessageOutputStreamWrapper.JsonOutputStreamWrapper;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.XattrProvider;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** A {@link SpawnLogContext} implementation that produces a log in expanded format. */
public class ExpandedSpawnLogContext implements SpawnLogContext {

  /** The log encoding. */
  public enum Encoding {
    /** Length-delimited binary protos. */
    BINARY,
    /** Newline-delimited JSON messages. */
    JSON
  }

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Path tempPath;
  private final boolean sorted;

  private final PathFragment execRoot;
  @Nullable private final RemoteOptions remoteOptions;
  private final DigestHashFunction digestHashFunction;
  private final XattrProvider xattrProvider;

  /** Output stream to write directly into during execution. */
  private final MessageOutputStream<SpawnExec> rawOutputStream;

  /** Output stream to convert the raw output stream into after execution, if required. */
  @Nullable private final MessageOutputStream<SpawnExec> convertedOutputStream;

  public ExpandedSpawnLogContext(
      Path outputPath,
      Path tempPath,
      Encoding encoding,
      boolean sorted,
      PathFragment execRoot,
      @Nullable RemoteOptions remoteOptions,
      DigestHashFunction digestHashFunction,
      XattrProvider xattrProvider)
      throws IOException {
    this.tempPath = tempPath;
    this.sorted = sorted;
    this.execRoot = execRoot;
    this.remoteOptions = remoteOptions;
    this.digestHashFunction = digestHashFunction;
    this.xattrProvider = xattrProvider;

    if (encoding == Encoding.BINARY && !sorted) {
      // The unsorted binary format can be written directly into the output path during execution.
      rawOutputStream = getRawOutputStream(outputPath);
      convertedOutputStream = null;
    } else {
      // Otherwise, write the unsorted binary format into a temporary path first, then convert into
      // the output format after execution.
      rawOutputStream = getRawOutputStream(tempPath);
      convertedOutputStream = getConvertedOutputStream(encoding, outputPath);
    }
  }

  private static MessageOutputStream<SpawnExec> getRawOutputStream(Path path) throws IOException {
    // Use an AsynchronousMessageOutputStream so that writes occur in a separate thread.
    // This ensures concurrent writes don't tear and avoids blocking execution.
    return new AsynchronousMessageOutputStream<>(path);
  }

  private static MessageOutputStream<SpawnExec> getConvertedOutputStream(
      Encoding encoding, Path path) throws IOException {
    switch (encoding) {
      case BINARY:
        return new BinaryOutputStreamWrapper<>(path.getOutputStream());
      case JSON:
        return new JsonOutputStreamWrapper<>(path.getOutputStream());
    }
    throw new IllegalArgumentException(
        String.format("invalid execution log encoding: %s", encoding));
  }

  @Override
  public void logSpawn(
      Spawn spawn,
      InputMetadataProvider inputMetadataProvider,
      SortedMap<PathFragment, ActionInput> inputMap,
      FileSystem fileSystem,
      Duration timeout,
      SpawnResult result)
      throws IOException, ExecException {
    SortedMap<Path, ActionInput> existingOutputs = listExistingOutputs(spawn, fileSystem);
    SpawnExec.Builder builder = SpawnExec.newBuilder();
    builder.addAllCommandArgs(spawn.getArguments());

    Map<String, String> env = spawn.getEnvironment();
    // Sorting the environment pairs by variable name.
    TreeSet<String> variables = new TreeSet<>(env.keySet());
    for (String var : variables) {
      builder.addEnvironmentVariablesBuilder().setName(var).setValue(env.get(var));
    }

    ImmutableSet<? extends ActionInput> toolFiles = spawn.getToolFiles().toSet();

    try (SilentCloseable c = Profiler.instance().profile("logSpawn/inputs")) {
      for (Map.Entry<PathFragment, ActionInput> e : inputMap.entrySet()) {
        ActionInput input = e.getValue();
        if (input instanceof VirtualActionInput.EmptyActionInput) {
          continue;
        }
        Path inputPath = fileSystem.getPath(execRoot.getRelative(input.getExecPathString()));
        if (inputPath.isDirectory()) {
          listDirectoryContents(inputPath, builder::addInputs, inputMetadataProvider);
          continue;
        }
        Digest digest =
            computeDigest(
                input, inputPath, inputMetadataProvider, xattrProvider, digestHashFunction);
        boolean isTool =
            toolFiles.contains(input)
                || (input instanceof TreeFileArtifact
                    && toolFiles.contains(((TreeFileArtifact) input).getParent()));
        builder
            .addInputsBuilder()
            .setPath(input.getExecPathString())
            .setDigest(digest)
            .setIsTool(isTool);
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Error computing spawn inputs");
    }
    try (SilentCloseable c = Profiler.instance().profile("logSpawn/outputs")) {
      ArrayList<String> outputPaths = new ArrayList<>();
      for (ActionInput output : spawn.getOutputFiles()) {
        outputPaths.add(output.getExecPathString());
      }
      Collections.sort(outputPaths);
      builder.addAllListedOutputs(outputPaths);
      for (Map.Entry<Path, ActionInput> e : existingOutputs.entrySet()) {
        Path path = e.getKey();
        if (path.isDirectory()) {
          listDirectoryContents(path, builder::addActualOutputs, inputMetadataProvider);
        } else {
          File.Builder outputBuilder = builder.addActualOutputsBuilder();
          outputBuilder.setPath(path.relativeTo(fileSystem.getPath(execRoot)).toString());
          try {
            outputBuilder.setDigest(
                computeDigest(
                    e.getValue(), path, inputMetadataProvider, xattrProvider, digestHashFunction));
          } catch (IOException ex) {
            logger.atWarning().withCause(ex).log("Error computing spawn event output properties");
          }
        }
      }
    }
    builder.setRemotable(Spawns.mayBeExecutedRemotely(spawn));

    Platform execPlatform = PlatformUtils.getPlatformProto(spawn, remoteOptions);
    if (execPlatform != null) {
      builder.setPlatform(buildPlatform(execPlatform));
    }
    if (result.status() != SpawnResult.Status.SUCCESS) {
      builder.setStatus(result.status().toString());
    }
    if (!timeout.isZero()) {
      builder.setTimeoutMillis(timeout.toMillis());
    }
    builder.setCacheable(Spawns.mayBeCached(spawn));
    builder.setRemoteCacheable(Spawns.mayBeCachedRemotely(spawn));
    builder.setExitCode(result.exitCode());
    builder.setCacheHit(result.isCacheHit());
    builder.setRunner(result.getRunnerName());

    if (result.getDigest() != null) {
      builder.setDigest(result.getDigest());
    }

    builder.setMnemonic(spawn.getMnemonic());

    if (spawn.getTargetLabel() != null) {
      builder.setTargetLabel(spawn.getTargetLabel().toString());
    }

    builder.setMetrics(getSpawnMetricsProto(result));

    try (SilentCloseable c = Profiler.instance().profile("logSpawn/write")) {
      rawOutputStream.write(builder.build());
    }
  }

  @Override
  public void close() throws IOException {
    rawOutputStream.close();

    if (convertedOutputStream == null) {
      // No conversion required.
      return;
    }

    try (InputStream in = tempPath.getInputStream()) {
      if (sorted) {
        StableSort.stableSort(in, convertedOutputStream);
      } else {
        while (in.available() > 0) {
          SpawnExec ex = SpawnExec.parseDelimitedFrom(in);
          convertedOutputStream.write(ex);
        }
      }
    } finally {
      try {
        tempPath.delete();
      } catch (IOException e) {
        // Intentionally ignored.
      }
    }
  }

  private static Protos.Platform buildPlatform(Platform platform) {
    Protos.Platform.Builder platformBuilder = Protos.Platform.newBuilder();
    for (Platform.Property p : platform.getPropertiesList()) {
      platformBuilder.addPropertiesBuilder().setName(p.getName()).setValue(p.getValue());
    }
    return platformBuilder.build();
  }

  private SortedMap<Path, ActionInput> listExistingOutputs(Spawn spawn, FileSystem fileSystem) {
    TreeMap<Path, ActionInput> result = new TreeMap<>();
    for (ActionInput output : spawn.getOutputFiles()) {
      Path outputPath = fileSystem.getPath(execRoot.getRelative(output.getExecPathString()));
      // TODO(olaola): once symlink API proposal is implemented, report symlinks here.
      if (outputPath.exists()) {
        result.put(outputPath, output);
      }
    }
    return result;
  }

  private void listDirectoryContents(
      Path path, Consumer<File> addFile, InputMetadataProvider inputMetadataProvider) {
    try {
      // TODO(olaola): once symlink API proposal is implemented, report symlinks here.
      List<Dirent> sortedDirent = new ArrayList<>(path.readdir(Symlinks.NOFOLLOW));
      sortedDirent.sort(Comparator.comparing(Dirent::getName));
      for (Dirent dirent : sortedDirent) {
        String name = dirent.getName();
        Path child = path.getRelative(name);
        if (dirent.getType() == Dirent.Type.DIRECTORY) {
          listDirectoryContents(child, addFile, inputMetadataProvider);
        } else {
          String pathString;
          if (child.startsWith(execRoot)) {
            pathString = child.asFragment().relativeTo(execRoot).getPathString();
          } else {
            pathString = child.getPathString();
          }
          addFile.accept(
              File.newBuilder()
                  .setPath(pathString)
                  .setDigest(
                      computeDigest(
                          null, child, inputMetadataProvider, xattrProvider, digestHashFunction))
                  .build());
        }
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Error computing spawn event file properties");
    }
  }
}
