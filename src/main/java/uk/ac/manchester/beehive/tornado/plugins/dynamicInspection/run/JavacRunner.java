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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Compiles the generated harnesses with the project JDK's {@code javac}.
 */
public final class JavacRunner {

    /** Upper bound for compiling the generated harnesses. */
    public static final int TIMEOUT_MS = 2 * 60 * 1000;

    /**
     * How a compilation ended.
     */
    public enum Status {
        /** The harnesses compiled. */
        OK,
        /** javac could not be started; {@link Result#detail()} says why. */
        LAUNCH_FAILED,
        /** javac did not finish within {@link #TIMEOUT_MS} and was stopped. */
        TIMED_OUT,
        /** javac reported errors; {@link Result#detail()} is its stderr. */
        ERRORS
    }

    /**
     * @param status   how the compilation ended
     * @param exitCode javac's exit code, or -1 if it did not exit by itself
     * @param detail   the launch failure message or the compiler's stderr
     */
    public record Result(@NotNull Status status, int exitCode, @NotNull String detail) {
    }

    private JavacRunner() {
    }

    /**
     * Builds the javac command line. {@code --release} follows the project JDK so
     * that {@code --enable-preview}, which requires it to match the running JDK,
     * stays valid.
     *
     * @param jdkHome   the project JDK home
     * @param release   the project JDK feature version
     * @param classpath the TornadoVM jars
     * @param outputDir where the class files go
     * @param javaFiles the harness sources
     * @param windows   whether to use {@code javac.exe}
     */
    public static @NotNull GeneralCommandLine commandLine(@NotNull String jdkHome, int release, @NotNull List<String> classpath,
                                                          @NotNull Path outputDir, @NotNull List<String> javaFiles,
                                                          boolean windows) {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        String javacPath = jdkHome + File.separator + "bin" + File.separator + "javac";
        if (windows) {
            javacPath += ".exe";
        }
        commandLine.setExePath(javacPath);
        commandLine.addParameter("--release");
        commandLine.addParameter(String.valueOf(release));
        commandLine.addParameter("--enable-preview");
        commandLine.addParameter("-g");
        commandLine.addParameter("-classpath");
        commandLine.addParameter(String.join(File.pathSeparator, classpath));
        commandLine.addParameter("-d");
        commandLine.addParameter(outputDir.toString());
        commandLine.addParameters(javaFiles);
        return commandLine;
    }

    /**
     * Runs {@code commandLine}.
     *
     * @throws com.intellij.openapi.progress.ProcessCanceledException if the user cancels
     */
    public static @NotNull Result run(@NotNull GeneralCommandLine commandLine, @NotNull ProgressIndicator indicator) {
        ProcessOutput output;
        try {
            output = Processes.run(commandLine, indicator, TIMEOUT_MS);
        } catch (ExecutionException e) {
            return new Result(Status.LAUNCH_FAILED, -1, String.valueOf(e.getMessage()));
        }
        if (output.isTimeout()) {
            return new Result(Status.TIMED_OUT, -1, output.getStderr());
        }
        if (output.getExitCode() != 0) {
            return new Result(Status.ERRORS, output.getExitCode(), output.getStderr());
        }
        return new Result(Status.OK, 0, output.getStderr());
    }

    /**
     * Returns the javac feature release to compile for, falling back to the
     * minimum supported release when the project JDK version is unknown.
     */
    public static int release(int projectJdkFeature) {
        return projectJdkFeature > 0 ? projectJdkFeature : 21;
    }

    /**
     * Returns the names of the required TornadoVM jars that are missing, in the
     * form used by the "TornadoVM JARs Not Found" message, or an empty string.
     */
    public static @NotNull String missingJars(@Nullable String api, @Nullable String matrices, @Nullable String unitTests) {
        String missing = "";
        if (api == null) missing += "tornado-api.jar ";
        if (matrices == null) missing += "tornado-matrices.jar ";
        if (unitTests == null) missing += "tornado-unittests.jar ";
        return missing;
    }
}
