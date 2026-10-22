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

package uk.ac.manchester.beehive.tornado.plugins.sdk;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * The default TornadoVM SDK, i.e. the one {@code TORNADOVM_HOME} points at.
 * <p>
 * The environment variable is read once, when the service is created. A relative
 * value is resolved against the user's home directory. The service locates the
 * launcher and the SDK jars, reads the SDK's JDK contract, and sets up the
 * environment of the processes dynamic inspection starts.
 * </p>
 */
@Service(Service.Level.APP)
public final class TornadoSdk {

    private static final Logger LOG = Logger.getInstance(TornadoSdk.class);

    public static final String HOME_VARIABLE = "TORNADOVM_HOME";

    private static final String[] JAR_DIRECTORY = {"share", "java", "tornado"};

    /**
     * The JDK contract of the SDK, or why it could not be determined.
     *
     * @param contract the contract, or {@code null} on failure
     * @param failure  a user-facing explanation when {@code contract} is {@code null}
     */
    public record JdkContractResult(@Nullable TornadoJdkContract contract, @Nullable String failure) {
    }

    private final @Nullable String homeValue;
    private final @Nullable Path home;
    private final boolean windows;

    public TornadoSdk() {
        this(System.getenv(HOME_VARIABLE), SystemInfo.isWindows);
    }

    private TornadoSdk(@Nullable String homeValue, boolean windows) {
        this.homeValue = homeValue == null || homeValue.isEmpty() ? null : homeValue;
        this.windows = windows;
        if (this.homeValue == null) {
            this.home = null;
        } else {
            Path path = Path.of(this.homeValue);
            this.home = path.isAbsolute() ? path : Path.of(System.getProperty("user.home")).resolve(path);
        }
    }

    public static @NotNull TornadoSdk getInstance() {
        return ApplicationManager.getApplication().getService(TornadoSdk.class);
    }

    /**
     * Returns an SDK for {@code homeValue} instead of {@code TORNADOVM_HOME}, e.g.
     * for tests.
     */
    public static @NotNull TornadoSdk forHome(@Nullable String homeValue, boolean windows) {
        return new TornadoSdk(homeValue, windows);
    }

    /**
     * Returns whether {@code TORNADOVM_HOME} is set to a non-empty value.
     */
    public boolean isConfigured() {
        return homeValue != null;
    }

    /**
     * Returns the value of {@code TORNADOVM_HOME}, or {@code null} if it is not set.
     */
    public @Nullable String homeValue() {
        return homeValue;
    }

    /**
     * Returns the SDK directory as an absolute path, or {@code null} if it is not set.
     */
    public @Nullable Path home() {
        return home;
    }

    public boolean isWindows() {
        return windows;
    }

    /**
     * Resolves the {@code tornado} executable. Prefers an absolute path under
     * {@code TORNADOVM_HOME/bin} (so the plugin works regardless of how the IDE
     * was launched, since the JVM resolves a bare command name against the
     * launching process's PATH rather than any PATH we set on the command line),
     * and falls back to the bare executable name for a PATH lookup when the home
     * is unknown or the binary is missing. On Windows it is {@code tornado.exe},
     * so the Unix shell script is never executed.
     */
    public @NotNull String launcherPath() {
        String exe = windows ? "tornado.exe" : "tornado";
        if (home != null) {
            Path candidate = home.resolve("bin").resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return exe;
    }

    /**
     * Finds a jar in {@code TORNADOVM_HOME/share/java/tornado} whose name starts
     * with {@code prefix}, e.g. {@code tornado-api}.
     *
     * @return the absolute path of the first match in name order, or {@code null}
     */
    public @Nullable String findJar(@NotNull String prefix) {
        if (home == null) {
            return null;
        }
        File jarDir = home.resolve(Path.of("", JAR_DIRECTORY)).toFile();
        File[] matches = jarDir.listFiles((dir, name) -> name.startsWith(prefix) && name.endsWith(".jar"));
        if (matches == null || matches.length == 0) {
            return null;
        }
        Arrays.sort(matches);
        return matches[0].getAbsolutePath();
    }

    public @Nullable String apiJar() {
        return findJar("tornado-api");
    }

    public @Nullable String matricesJar() {
        return findJar("tornado-matrices");
    }

    public @Nullable String unitTestsJar() {
        return findJar("tornado-unittests");
    }

    /**
     * Creates a command line for the {@code tornado} launcher with the
     * environment of {@link #configureEnvironment}.
     */
    public @NotNull GeneralCommandLine launcherCommand(@Nullable String javaHome, @NotNull String... args) {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        commandLine.setExePath(launcherPath());
        configureEnvironment(commandLine, javaHome);
        commandLine.addParameters(args);
        return commandLine;
    }

    /**
     * Sets {@code JAVA_HOME} (when known) and {@code TORNADOVM_HOME} for a child
     * process, and puts {@code TORNADOVM_HOME/bin} first on its {@code PATH}.
     */
    public void configureEnvironment(@NotNull GeneralCommandLine commandLine, @Nullable String javaHome) {
        Map<String, String> environment = commandLine.getEnvironment();
        if (javaHome != null) {
            environment.put("JAVA_HOME", javaHome);
        }
        if (homeValue != null) {
            environment.put(HOME_VARIABLE, homeValue);
            String existingPath = environment.get("PATH");
            if (existingPath == null) {
                existingPath = System.getenv("PATH");
            }
            String tornadoBinPath = homeValue + File.separator + "bin";
            environment.put("PATH", existingPath == null ? tornadoBinPath : tornadoBinPath + File.pathSeparator + existingPath);
        }
    }

    /**
     * Reads the JDK contract from {@code etc/tornado.jdk} (TornadoVM &ge; 6.0.0).
     *
     * @return the contract, or {@code null} if the file is missing or unusable
     */
    public @Nullable TornadoJdkContract contractFromFile() {
        if (home == null) {
            return null;
        }
        Path contractFile = home.resolve(Path.of("", TornadoJdkContract.CONTRACT_FILE_PATH));
        if (!Files.isRegularFile(contractFile)) {
            return null;
        }
        try {
            TornadoJdkContract contract = TornadoJdkContract.parseContractFile(Files.readString(contractFile));
            if (contract == null) {
                LOG.warn("Could not parse " + contractFile + ": no usable tornado.jdk.floor");
            }
            return contract;
        } catch (IOException e) {
            LOG.warn("Could not parse " + contractFile + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Determines the JDK compatibility contract of the SDK. Prefers
     * {@code etc/tornado.jdk}; falls back to running {@code tornado --version}
     * (see {@link TornadoJdkContract#parseVersionOutput}) for older SDKs.
     *
     * @param javaHome  the JDK the launcher is run with
     * @param timeoutMs the time {@code tornado --version} may take
     * @throws ProcessCanceledException if {@code indicator} is cancelled
     */
    public @NotNull JdkContractResult jdkContract(@NotNull ProgressIndicator indicator, @Nullable String javaHome,
                                                  int timeoutMs) {
        TornadoJdkContract fromFile = contractFromFile();
        if (fromFile != null) {
            return new JdkContractResult(fromFile, null);
        }

        ProcessOutput output;
        try {
            output = new CapturingProcessHandler(launcherCommand(javaHome, "--version"))
                    .runProcessWithProgressIndicator(indicator, timeoutMs);
        } catch (ExecutionException e) {
            return new JdkContractResult(null, "Failed to launch 'tornado --version': " + e.getMessage()
                    + ". Verify that 'tornado' is on PATH and that TORNADOVM_HOME points at a valid installation.");
        }
        if (output.isCancelled()) {
            throw new ProcessCanceledException();
        }
        if (output.isTimeout()) {
            return new JdkContractResult(null, "'tornado --version' did not finish within "
                    + timeoutMs / 1000 + " seconds and was stopped.");
        }

        String combined = (output.getStdout() + "\n" + output.getStderr()).trim();
        TornadoJdkContract contract = TornadoJdkContract.parseVersionOutput(combined);
        if (contract != null) {
            return new JdkContractResult(contract, null);
        }

        // Neither source produced a usable version - report the raw output.
        String prefix = output.getExitCode() != 0
                ? "'tornado --version' exited with code " + output.getExitCode() + "."
                : "'tornado --version' did not report a JDK target, and $TORNADOVM_HOME/etc/tornado.jdk was missing or unreadable.";
        return new JdkContractResult(null, prefix + "\nOutput:\n" + combined);
    }
}
