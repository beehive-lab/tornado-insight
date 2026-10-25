/*
 * Copyright (c) 2026, APT Group, Department of Computer Science,
 *  The University of Manchester.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.dynamic.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.nio.file.Path;

/**
 * Runs the {@code tornado} launcher of an SDK with the project JDK. Every process
 * runs in {@code workDir}, which should be empty: the launcher puts its working
 * directory on the module path, so stray jars there break the boot layer.
 */
public final class TornadoRunner {

    /** Upper bound for running one harness on TornadoVM. */
    public static final int RUN_TIMEOUT_MS = 5 * 60 * 1000;
    /** Upper bound for 'tornado --version' and 'tornado --device'. */
    public static final int PROBE_TIMEOUT_MS = 60 * 1000;

    private final TornadoSdk sdk;
    private final @Nullable String javaHome;
    private final Path workDir;

    public TornadoRunner(@NotNull TornadoSdk sdk, @Nullable String javaHome, @NotNull Path workDir) {
        this.sdk = sdk;
        this.javaHome = javaHome;
        this.workDir = workDir;
    }

    /**
     * The command that lists the devices, used to check that TornadoVM works at all.
     */
    public @NotNull GeneralCommandLine deviceCheckCommand() {
        return command("--device");
    }

    /**
     * The command that runs a harness jar and prints the generated kernel.
     */
    public @NotNull GeneralCommandLine runCommand(@NotNull Path jar) {
        return command("--printKernel", "-jar", jar.toString());
    }

    private GeneralCommandLine command(String... args) {
        GeneralCommandLine commandLine = sdk.launcherCommand(javaHome, args);
        commandLine.setWorkDirectory(workDir.toFile());
        return commandLine;
    }

    /**
     * Runs {@code commandLine} (see {@link Processes#run}).
     */
    public static @NotNull ProcessOutput run(@NotNull GeneralCommandLine commandLine, @NotNull ProgressIndicator indicator,
                                             int timeoutMs) throws ExecutionException {
        return Processes.run(commandLine, indicator, timeoutMs);
    }
}
