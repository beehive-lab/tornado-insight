/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.CodeGenerator.Batch;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.CodeGenerator.Harness;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.run.JarPacker;
import uk.ac.manchester.beehive.tornado.plugins.entity.EnvironmentVariable;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoJdkContract;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingConfiguration;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageUtils;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoNotifications;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTaskService.TaskEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs dynamic inspection for the selected tasks: checks the project JDK and the
 * TornadoVM installation, generates a test harness per task, compiles them with
 * {@code javac}, packs each into a jar and runs it with {@code tornado}.
 * <p>
 * {@link #start} does the quick checks on the UI thread and queues the rest as a
 * cancellable background task. The PSI is read once, in a non-blocking read action,
 * into plain {@link Harness} data; no PSI element is used after that. Every stage
 * reports its own expected failures on the console or in a notification and tells
 * the caller whether to go on; nothing is rethrown to the IDE.
 * </p>
 */
public final class ExecutionEngine {

    private static final Logger LOG = Logger.getInstance(ExecutionEngine.class);

    /** Upper bound for compiling the generated harnesses. */
    static final int JAVAC_TIMEOUT_MS = 2 * 60 * 1000;
    /** Upper bound for running one harness on TornadoVM. */
    static final int TORNADO_RUN_TIMEOUT_MS = 5 * 60 * 1000;
    /** Upper bound for 'tornado --version' and 'tornado --device'. */
    static final int TORNADO_PROBE_TIMEOUT_MS = 60 * 1000;

    /**
     * The project JDK, copied on the UI thread so the background task does not
     * query the project model.
     *
     * @param name          the SDK name, may be {@code null}
     * @param homePath      the JDK home, may be {@code null}
     * @param versionString the SDK version string, may be {@code null}
     * @param feature       the JDK feature version, or -1 if unknown
     */
    record ProjectJdk(@Nullable String name, @Nullable String homePath, @Nullable String versionString, int feature) {
    }

    private final Project project;
    private final ProjectJdk jdk;
    private final List<TaskEntry> tasks;
    private final boolean saveFileEnabled;
    private final String debugFileSaveLocation;

    private boolean completed;
    private boolean hasRuntimeErrors;

    private ExecutionEngine(Project project, ProjectJdk jdk, List<TaskEntry> tasks,
                            boolean saveFileEnabled, String debugFileSaveLocation) {
        this.project = project;
        this.jdk = jdk;
        this.tasks = List.copyOf(tasks);
        this.saveFileEnabled = saveFileEnabled;
        this.debugFileSaveLocation = debugFileSaveLocation;
    }

    /**
     * Starts dynamic inspection of {@code tasks}. Called on the UI thread.
     */
    public static void start(@NotNull Project project, @NotNull List<TaskEntry> tasks) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.start"));

        // Validate JDK before starting
        ProjectJdk jdk = validateProjectJdk(project);
        if (jdk == null) {
            return;
        }

        // Validate TornadoVM SDK before starting
        if (!validateTornadoSdk(project)) {
            return;
        }

        // Make sure the configured test-data size will produce a meaningful run
        if (!validateParameterSize(project)) {
            return;
        }

        TornadoSettingState settings = TornadoSettingState.getInstance();
        ExecutionEngine engine = new ExecutionEngine(project, jdk, tasks,
                settings.saveFileEnabled, settings.debugFileSaveLocation);
        new Task.Backgroundable(project, "Running TornadoVM dynamic inspection", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                engine.run(indicator);
            }

            @Override
            public void onCancel() {
                MessageUtils.getInstance(project).showWarnMsg(MessageBundle.message("dynamic.info.title"),
                        "Dynamic inspection was cancelled.");
            }
        }.queue();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Resolves the {@code tornado} executable. Prefers an absolute path under
     * {@code TORNADOVM_HOME/bin} (so the plugin works regardless of how the IDE
     * was launched, since the JVM resolves a bare command name against the
     * launching process's PATH rather than any PATH we set on the command line),
     * and falls back to the bare executable name for a PATH lookup when the home
     * is unknown or the binary is missing.
     */
    private static String resolveTornadoExe() {
        String exe = isWindows() ? "tornado.exe" : "tornado";
        String home = EnvironmentVariable.getTornadoSdk();
        if (home != null && !home.isEmpty()) {
            File candidate = new File(home, "bin" + File.separator + exe);
            if (candidate.isFile()) {
                return candidate.getAbsolutePath();
            }
        }
        return exe;
    }

    /**
     * The background part of the pipeline. Expected failures stop it after being
     * reported; cancellation is propagated to the task.
     */
    private void run(@NotNull ProgressIndicator indicator) {
        long startTime = System.currentTimeMillis();
        Path workDir = null;
        try {
            indicator.setIndeterminate(true);
            indicator.setText("Checking the TornadoVM installation");
            if (!validateJdkCompatibility(indicator)) {
                return;
            }

            indicator.checkCanceled();
            indicator.setText("Generating test code");
            Batch batch = ReadAction.nonBlocking(() -> CodeGenerator.generate(tasks))
                    .inSmartMode(project)
                    .expireWhen(project::isDisposed)
                    .wrapProgress(indicator)
                    .executeSynchronously();
            for (String stale : batch.staleTasks()) {
                reportStaleTask(stale);
            }
            List<Harness> harnesses = batch.harnesses();
            if (harnesses.isEmpty()) {
                return;
            }

            workDir = FileUtil.createTempDirectory("tornado-insight", null, false).toPath();
            Path sourceDir = Files.createDirectory(workDir.resolve("src"));
            Path classDir = Files.createDirectory(workDir.resolve("classes"));
            Path jarDir = Files.createDirectory(workDir.resolve("jars"));
            // tornado puts its working directory on the module path, so run it in an empty one
            Path runDir = Files.createDirectory(workDir.resolve("run"));

            List<String> sourceFiles = writeSources(harnesses, sourceDir);

            indicator.checkCanceled();
            indicator.setText(MessageBundle.message("dynamic.info.compile"));
            if (!compile(indicator, classDir, sourceFiles)) {
                return;
            }

            indicator.checkCanceled();
            indicator.setText(MessageBundle.message("dynamic.info.packing"));
            Map<Harness, Path> jars = packJars(harnesses, classDir, jarDir);
            if (jars == null) {
                return;
            }

            indicator.checkCanceled();
            indicator.setText(MessageBundle.message("dynamic.info.execution"));
            executeJars(indicator, jars, runDir);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (IOException e) {
            LOG.warn("Dynamic inspection failed", e);
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    "Dynamic inspection failed: " + e.getMessage());
        } catch (RuntimeException e) {
            if (e instanceof ControlFlowException) {
                throw e;
            }
            LOG.error("Dynamic inspection failed unexpectedly", e);
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    "Dynamic inspection failed unexpectedly: " + e);
        } finally {
            long runningTime = System.currentTimeMillis() - startTime;
            showStatDialog(runningTime);
            cleanUp(workDir);
        }
    }

    // A task in the panel whose method (or file) was deleted or changed beyond
    // recognition since the last refresh
    private void reportStaleTask(String task) {
        MessageUtils.getInstance(project).showWarnMsg(MessageBundle.message("dynamic.info.title"),
                "Task " + task + " no longer exists in the source. Refresh the TornadoVM panel and try again.");
    }

    private List<String> writeSources(List<Harness> harnesses, Path sourceDir) throws IOException {
        List<String> files = new ArrayList<>();
        for (Harness harness : harnesses) {
            Path file = sourceDir.resolve(harness.className() + ".java");
            Files.writeString(file, harness.source());
            files.add(file.toString());
            if (saveFileEnabled) {
                saveFileToDisk(harness);
            }
        }
        return files;
    }

    // Keeps a copy of the generated harness for debugging (Settings > Save Internal Debug File)
    private void saveFileToDisk(Harness harness) {
        if (debugFileSaveLocation == null || debugFileSaveLocation.isBlank()) {
            return;
        }
        Path target = Path.of(debugFileSaveLocation, harness.className() + ".java");
        try {
            Files.writeString(target, harness.source());
        } catch (IOException e) {
            LOG.warn("Could not save the generated test code to " + target, e);
            MessageUtils.getInstance(project).showWarnMsg(MessageBundle.message("dynamic.info.title"),
                    "Could not save the generated test code to " + target + ": " + e.getMessage());
        }
    }

    /**
     * Runs {@code commandLine} to completion, or until {@code timeoutMs} passes or
     * the user cancels. A cancelled run throws {@link ProcessCanceledException}; a
     * timed-out one returns an output whose {@link ProcessOutput#isTimeout()} is set
     * (the process is destroyed).
     */
    private static ProcessOutput runProcess(GeneralCommandLine commandLine, ProgressIndicator indicator, int timeoutMs)
            throws ExecutionException {
        ProcessOutput output = new CapturingProcessHandler(commandLine)
                .runProcessWithProgressIndicator(indicator, timeoutMs);
        if (output.isCancelled()) {
            throw new ProcessCanceledException();
        }
        return output;
    }

    private static String seconds(int timeoutMs) {
        return timeoutMs / 1000 + " seconds";
    }

    private static void openProjectStructureAction(Project project, Notification notification) {
        notification.addAction(new NotificationAction("Open Project Structure") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                ProjectSettingsService.getInstance(project).openProjectSettings();
            }
        });
    }

    private static @Nullable ProjectJdk validateProjectJdk(Project project) {
        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();

        if (projectSdk == null) {
            Notification notification = TornadoNotifications.create(
                "No Project JDK Configured",
                "Please configure JDK 21 or newer in Project Structure (File > Project Structure > Project Settings > Project)",
                NotificationType.ERROR
            );
            openProjectStructureAction(project, notification);
            Notifications.Bus.notify(notification, project);
            return null;
        }

        JavaSdkVersion sdkVersion = JavaSdkVersion.fromVersionString(projectSdk.getVersionString());
        if (sdkVersion == null || sdkVersion.compareTo(JavaSdkVersion.JDK_21) < 0) {
            Notification notification = TornadoNotifications.create(
                "Incompatible JDK Version",
                "TornadoVM requires JDK 21 or newer. Current project JDK: " +
                    (sdkVersion != null ? sdkVersion.getDescription() : projectSdk.getVersionString()) +
                    ". Please update your project JDK in Project Structure.",
                NotificationType.ERROR
            );
            openProjectStructureAction(project, notification);
            Notifications.Bus.notify(notification, project);
            return null;
        }

        return new ProjectJdk(projectSdk.getName(), projectSdk.getHomePath(), projectSdk.getVersionString(),
                sdkVersion.getMaxLanguageLevel().toJavaVersion().feature);
    }

    private static boolean validateTornadoSdk(Project project) {
        String tornadoSdk = System.getenv("TORNADOVM_HOME");

        // Show TORNADOVM_HOME detection info
        MessageUtils.getInstance(project).showInfoMsg("Info",
                "TORNADOVM_HOME detected: " + (tornadoSdk != null ? tornadoSdk : "NOT SET"));

        if (tornadoSdk == null || tornadoSdk.isEmpty()) {
            LOG.warn("TORNADOVM_HOME is not set or empty");
            Notification notification = TornadoNotifications.create(
                "TornadoVM SDK Not Configured",
                "<html>TORNADOVM_HOME environment variable is not set.<br><br>" +
                    "Please:<br>" +
                    "1. Download and install TornadoVM from <a href='https://www.tornadovm.org/downloads'>tornadovm.org/downloads</a><br>" +
                    "2. Set the TORNADOVM_HOME environment variable<br>" +
                    "3. Restart your IntelliJ session</html>",
                NotificationType.ERROR
            );
            Notifications.Bus.notify(notification, project);
            return false;
        }

        return true;
    }

    /**
     * Refuses to launch dynamic inspection when the configured test-data size
     * is non-positive (or absurdly large). {@code VariableInit} pipes this
     * value into every synthetic allocator it emits (arrays, matrices, images,
     * vectors), so a zero would generate empty inputs that compile and "run"
     * cleanly without actually exercising the kernel - a misleading green
     * tick. The Settings dialog validates this on Apply, but nothing else
     * does, so a fresh install or a hand-edited state file can still leak a
     * bad value through to the runtime path.
     */
    private static boolean validateParameterSize(Project project) {
        int size = TornadoSettingState.getInstance().parameterSize;
        int maxSize = TornadoSettingState.MAX_PARAMETER_SIZE - 1;
        if (size > 0 && size <= maxSize) {
            return true;
        }

        String detail = size <= 0
                ? "Current value is " + size + ", which would generate empty inputs and produce a misleading green run."
                : "Current value is " + size + ", which exceeds the supported maximum (" + maxSize + ").";
        Notification notification = TornadoNotifications.create(
                "Invalid Max Array Size",
                "Set 'Max array size' in TornadoInsight settings to a value between 1 and " + maxSize + " (recommended: 128). "
                        + detail,
                NotificationType.ERROR
        );
        notification.addAction(new ChangeParameterSize());
        Notifications.Bus.notify(notification, project);
        return false;
    }

    /**
     * Human-readable identity of the project JDK for diagnostic messages, e.g.
     * {@code "temurin-25 (25.0.4) [GraalVM]"}. Falls back to the feature number
     * when the SDK exposes neither a name nor a version string.
     */
    private static String describeProjectJdk(ProjectJdk jdk) {
        String name = jdk.name();
        String version = jdk.versionString();
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isBlank()) {
            sb.append(name.trim());
        }
        if (version != null && !version.isBlank() && !version.trim().equals(name)) {
            sb.append(sb.isEmpty() ? version.trim() : " (" + version.trim() + ")");
        }
        if (sb.isEmpty()) {
            sb.append("JDK ").append(jdk.feature());
        }
        if (isGraalVmDistribution(jdk.homePath())) {
            sb.append(" [GraalVM]");
        }
        return sb.toString();
    }

    /**
     * Cross-checks the JDK compatibility contract of the default TornadoVM SDK
     * against the JDK currently configured for the project. Only the JDK feature
     * version is compared &mdash; vendor (Temurin, GraalVM, Oracle, Zulu, ...) is
     * intentionally ignored, since TornadoVM's compatibility is keyed on the
     * language/runtime version. Mismatches typically manifest as runtime errors
     * deep inside TornadoVM, so failing fast here gives a clearer diagnostic.
     */
    private boolean validateJdkCompatibility(ProgressIndicator indicator) {
        TornadoJdkContract contract = detectTornadoVmJdkContract(indicator);
        if (contract == null) {
            // detectTornadoVmJdkContract already surfaced a notification with details
            return false;
        }

        int projectJdk = jdk.feature();

        MessageUtils.getInstance(project).showInfoMsg("Info",
                "TornadoVM requires " + contract.requirement() + " (from " + contract.source() + "); project JDK is "
                        + describeProjectJdk(jdk) + ".");

        if (!contract.accepts(projectJdk)) {
            Notification notification = TornadoNotifications.create(
                "Incompatible JDK for default TornadoVM",
                "Current project JDK " + projectJdk +
                    " is not compatible with the default TornadoVM at " + System.getenv("TORNADOVM_HOME") +
                    ", which requires " + contract.requirement() + "." +
                    " Either change the project JDK in Project Structure, or point TORNADOVM_HOME at a" +
                    " TornadoVM build that supports JDK " + projectJdk + ".",
                NotificationType.ERROR
            );
            openProjectStructureAction(project, notification);
            Notifications.Bus.notify(notification, project);
            return false;
        }

        if (contract.vendorsGraal() && isGraalVmDistribution(jdk.homePath())) {
            Notification notification = TornadoNotifications.create(
                "Incompatible JDK distribution for default TornadoVM",
                "The project JDK at " + jdk.homePath() + " is a GraalVM build. TornadoVM 6.0.0 and" +
                    " newer bundle their own Graal compiler and JVMCI, which collide with the modules a" +
                    " GraalVM JDK already ships and abort the JVM (fatal error: HotSpotNmethod.profileDeopt)." +
                    " Set the project JDK to a standard OpenJDK build (e.g. Eclipse Temurin or Oracle OpenJDK)," +
                    " " + contract.requirement() + ", in Project Structure.",
                NotificationType.ERROR
            );
            openProjectStructureAction(project, notification);
            Notifications.Bus.notify(notification, project);
            return false;
        }

        return true;
    }

    /**
     * Detects a GraalVM JDK (Community or Oracle) from the {@code release} file
     * in its home directory (see {@link TornadoJdkContract#isGraalVmRelease}).
     * Recognising this lets us refuse with a clear message instead of a core dump.
     */
    private static boolean isGraalVmDistribution(@Nullable String home) {
        if (home == null || home.isEmpty()) {
            return false;
        }
        Path release = Path.of(home, "release");
        if (!Files.isRegularFile(release)) {
            return false;
        }
        try {
            return TornadoJdkContract.isGraalVmRelease(Files.readAllLines(release));
        } catch (IOException ignore) {
            // Cannot read it - assume a stock JDK rather than block the run.
        }
        return false;
    }

    /**
     * Determines the JDK compatibility contract of the default TornadoVM SDK.
     * Prefers {@code $TORNADOVM_HOME/etc/tornado.jdk} (TornadoVM &ge; 6.0.0);
     * falls back to parsing {@code tornado --version} for older SDKs. Returns
     * {@code null} (and surfaces a user-visible notification) when neither
     * source yields a usable answer.
     */
    private @Nullable TornadoJdkContract detectTornadoVmJdkContract(ProgressIndicator indicator) {
        String home = EnvironmentVariable.getTornadoSdk();
        if (home != null && !home.isEmpty()) {
            Path contractFile = Path.of(home, TornadoJdkContract.CONTRACT_FILE_PATH);
            if (Files.isRegularFile(contractFile)) {
                try {
                    TornadoJdkContract contract = TornadoJdkContract.parseContractFile(Files.readString(contractFile));
                    if (contract != null) {
                        return contract;
                    }
                    LOG.warn("Could not parse " + contractFile + ": no usable tornado.jdk.floor");
                } catch (IOException e) {
                    LOG.warn("Could not parse " + contractFile + ": " + e.getMessage());
                }
                // fall through to the 'tornado --version' probe
            }
        }
        return probeTornadoJdkContractViaVersion(indicator);
    }

    /**
     * Fallback for SDKs without {@code etc/tornado.jdk}: runs
     * {@code tornado --version} (see {@link TornadoJdkContract#parseVersionOutput}).
     * Returns {@code null} (and surfaces a user-visible notification) when the
     * subprocess cannot be launched, fails, times out or yields no recognisable marker.
     */
    private @Nullable TornadoJdkContract probeTornadoJdkContractViaVersion(ProgressIndicator indicator) {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        commandLine.setExePath(resolveTornadoExe());
        configureEnvironmentVariables(commandLine);
        commandLine.addParameter("--version");

        ProcessOutput output;
        try {
            output = runProcess(commandLine, indicator, TORNADO_PROBE_TIMEOUT_MS);
        } catch (ExecutionException e) {
            notifyTornadoVersionFailure(
                    "Failed to launch 'tornado --version': " + e.getMessage()
                            + ". Verify that 'tornado' is on PATH and that TORNADOVM_HOME points at a valid installation.");
            return null;
        }
        if (output.isTimeout()) {
            notifyTornadoVersionFailure("'tornado --version' did not finish within "
                    + seconds(TORNADO_PROBE_TIMEOUT_MS) + " and was stopped.");
            return null;
        }

        String combined = (output.getStdout() + "\n" + output.getStderr()).trim();
        TornadoJdkContract contract = TornadoJdkContract.parseVersionOutput(combined);
        if (contract != null) {
            return contract;
        }

        // Neither source produced a usable version - report the raw output.
        String prefix = output.getExitCode() != 0
                ? "'tornado --version' exited with code " + output.getExitCode() + "."
                : "'tornado --version' did not report a JDK target, and $TORNADOVM_HOME/etc/tornado.jdk was missing or unreadable.";
        notifyTornadoVersionFailure(prefix + "\nOutput:\n" + combined);
        return null;
    }

    private void notifyTornadoVersionFailure(String detail) {
        LOG.warn("tornado --version check failed: " + detail);
        Notification notification = TornadoNotifications.create(
                "TornadoVM installation check failed",
                "<html>Could not determine the JDK that TornadoVM was built against.<br><br>" +
                        detail.replace("\n", "<br>") + "</html>",
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
    }

    /**
     * Compiles the harnesses into {@code outputDir}. A failure is reported (with
     * the summarised javac errors) and stops the pipeline.
     *
     * @return whether compilation succeeded
     */
    private boolean compile(ProgressIndicator indicator, Path outputDir, List<String> javaFiles) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.compile"));

        // Build classpath with null checks
        TornadoSettingState settings = TornadoSettingState.getInstance();

        String apiPath = settings.getApiPath();
        String matricesPath = settings.getMatricesPath();
        String unitTestPath = settings.getUnitTestPath();

        if (apiPath == null || matricesPath == null || unitTestPath == null) {
            String missingJars = "";
            if (apiPath == null) missingJars += "tornado-api.jar ";
            if (matricesPath == null) missingJars += "tornado-matrices.jar ";
            if (unitTestPath == null) missingJars += "tornado-unittests.jar ";

            String errorMsg = "Could not find required TornadoVM JARs in $TORNADOVM_HOME/share/java/tornado/\n" +
                "Missing: " + missingJars + "\n\n" +
                "Please check that:\n" +
                "1. TORNADOVM_HOME environment variable points to the correct TornadoVM SDK directory\n" +
                "2. The directory contains share/java/tornado/ with the required JAR files\n\n" +
                "For installation instructions, visit: https://www.tornadovm.org/downloads";

            MessageUtils.getInstance(project).showErrorMsg("TornadoVM JARs Not Found", errorMsg);
            return false;
        }

        String classpath = apiPath + File.pathSeparator + matricesPath + File.pathSeparator + unitTestPath;

        // Derive --release from the project SDK so it stays in sync with the JDK
        // actually used to compile (and so that --enable-preview, which requires
        // --release to match the current JDK, remains valid).
        int releaseVersion = jdk.feature() > 0 ? jdk.feature() : 21; // Fallback to the minimum supported release

        GeneralCommandLine commandLine = new GeneralCommandLine();
        String javacPath = jdk.homePath() + File.separator + "bin" + File.separator + "javac";
        if (isWindows()) {
            javacPath += ".exe";
        }
        commandLine.setExePath(javacPath);
        commandLine.addParameter("--release");
        commandLine.addParameter(String.valueOf(releaseVersion));
        commandLine.addParameter("--enable-preview");
        commandLine.addParameter("-g");
        commandLine.addParameter("-classpath");
        commandLine.addParameter(classpath);
        commandLine.addParameter("-d");
        commandLine.addParameter(outputDir.toString());
        commandLine.addParameters(javaFiles);  // Adds each Java file to the command line

        ProcessOutput output;
        try {
            output = runProcess(commandLine, indicator, JAVAC_TIMEOUT_MS);
        } catch (ExecutionException e) {
            LOG.warn("Could not run javac", e);
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    MessageBundle.message("dynamic.error.compile") + "\n" + e.getMessage());
            return false;
        }
        if (output.isTimeout()) {
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    "Compiling the generated test code did not finish within " + seconds(JAVAC_TIMEOUT_MS)
                            + " and was stopped.");
            return false;
        }
        int exitCode = output.getExitCode();
        if (exitCode != 0) {
            // Surface a concise, human-readable warning instead of dumping
            // the raw javac output. A type mismatch in the generated harness
            // (e.g. a kernel parameter whose type the device does not
            // support, such as FP64/double on a GPU without double support)
            // produces hundreds of lines of overload candidates and
            // type-variable declarations that bury the actual cause.
            LOG.warn("Generated code compilation failed (exit code " + exitCode + "):\n" + output.getStderr());
            MessageUtils.getInstance(project).showWarnMsg("Generated code did not compile",
                    CompilerOutputSummarizer.summarize(output.getStderr()));
            return false;
        }
        return true;
    }

    /**
     * Packs each compiled harness, with its nested and anonymous classes, into its
     * own executable jar.
     *
     * @return the jar of each harness, or {@code null} if packing failed (reported)
     */
    private @Nullable Map<Harness, Path> packJars(List<Harness> harnesses, Path classDir, Path jarDir) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.packing"));
        Map<Harness, Path> jars = new LinkedHashMap<>();
        for (Harness harness : harnesses) {
            Path jar = jarDir.resolve(harness.className() + ".jar");
            try {
                JarPacker.pack(classDir, harness.className(), jar);
            } catch (IOException e) {
                LOG.warn("Could not pack " + harness.className(), e);
                MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                        MessageBundle.message("dynamic.error.packing") + ": " + e.getMessage());
                return null;
            }
            jars.put(harness, jar);
        }
        return jars;
    }

    private void executeJars(ProgressIndicator indicator, Map<Harness, Path> jars, Path runDir) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.execution"));
        GeneralCommandLine commandLine = new GeneralCommandLine();
        //Detecting if the user has correctly installed TornadoVM
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        // On Windows, explicitly use tornado.exe to avoid trying to execute the Unix shell script
        commandLine.setExePath(resolveTornadoExe());
        commandLine.setWorkDirectory(runDir.toFile());
        configureEnvironmentVariables(commandLine);
        commandLine.addParameter("--device");

        try {
            ProcessOutput output = runProcess(commandLine, indicator, TORNADO_PROBE_TIMEOUT_MS);
            if (output.isTimeout() || output.getExitCode() != 0) {
                // TornadoVM is not properly installed on the user's machine
                Notification notification = TornadoNotifications.create("TornadoVM not detected",
                        "TornadoVM is not properly installed or configured", NotificationType.ERROR);
                notification.addAction(new NotificationAction("How to install and configure TornadoVM") {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                        BrowserUtil.browse("https://tornadovm.readthedocs.io/en/latest/installation.html#");
                    }
                });
                Notifications.Bus.notify(notification, project);
                return;
            }
        } catch (ExecutionException e) {
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    "TornadoVM is not properly installed or configured.\n\n" +
                    "Error: " + e.getMessage());

            return;
        }

        int done = 0;
        indicator.setIndeterminate(false);
        for (Map.Entry<Harness, Path> entry : jars.entrySet()) {
            indicator.checkCanceled();
            indicator.setText2(entry.getKey().displayName());
            indicator.setFraction((double) done++ / jars.size());
            runTornadoOnJar(indicator, entry.getKey(), entry.getValue(), runDir);
        }
    }

    private void runTornadoOnJar(ProgressIndicator indicator, Harness harness, Path jar, Path runDir) {
        GeneralCommandLine commandLine = getGeneralCommandLine(jar.toString());
        commandLine.setWorkDirectory(runDir.toFile());
        ProcessOutput output;
        try {
            output = runProcess(commandLine, indicator, TORNADO_RUN_TIMEOUT_MS);
        } catch (ExecutionException e) {
            LOG.warn("Could not run tornado on " + jar, e);
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    harness.displayName() + ": could not run TornadoVM: " + e.getMessage());
            return;
        }
        if (output.isTimeout()) {
            hasRuntimeErrors = true;
            completed = true;
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    harness.displayName() + ": TornadoVM did not finish within " + seconds(TORNADO_RUN_TIMEOUT_MS)
                            + "; the run was stopped.");
            return;
        }
        // Cannot use the exit code alone to determine if TornadoVM hit an error.
        // Under normal circumstances TornadoVM output includes warnings like:
        // "WARNING: Using incubator modules: jdk.incubator.foreign, jdk.incubator.vector"
        // We must check for both Java exceptions and OpenCL/SPIR-V/PTX runtime errors.
        RuntimeOutputClassifier.Verdict verdict =
                RuntimeOutputClassifier.classify(output.getStdout(), output.getStderr(), output.getExitCode());
        printResults(harness.displayName(), verdict, output);
    }

    @NotNull
    private GeneralCommandLine getGeneralCommandLine(String jarPath) {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        // On Windows, explicitly use tornado.exe to avoid trying to execute the Unix shell script
        commandLine.setExePath(resolveTornadoExe());
        configureEnvironmentVariables(commandLine);
        commandLine.addParameter("--printKernel");
        commandLine.addParameter("-jar");
        commandLine.addParameter(jarPath);

        return commandLine;
    }

    private void configureEnvironmentVariables(GeneralCommandLine commandLine) {
        Map<String, String> environment = commandLine.getEnvironment();

        // Set JAVA_HOME
        String javaHome = jdk.homePath();
        if (javaHome != null) {
            environment.put("JAVA_HOME", javaHome);
        }

        // Set TORNADOVM_HOME and update PATH
        String tornadoVmHome = EnvironmentVariable.getTornadoSdk();

        if (tornadoVmHome != null) {
            environment.put("TORNADOVM_HOME", tornadoVmHome);

            // Add tornado/bin to PATH
            String existingPath = environment.get("PATH");
            if (existingPath == null) {
                existingPath = System.getenv("PATH");
            }

            String tornadoBinPath = tornadoVmHome + File.separator + "bin";
            String pathSeparator = File.pathSeparator;
            String newPath = tornadoBinPath + pathSeparator + existingPath;
            environment.put("PATH", newPath);
        } else {
            // Fallback: use parsed PATH from setvars.sh (Unix only)
            String envPath = EnvironmentVariable.getPath();
            if (envPath != null) {
                environment.put("PATH", envPath);
            }
        }

        // Set CMAKE_ROOT if available
        String cmakeRoot = EnvironmentVariable.getCmakeRoot();
        if (cmakeRoot != null) {
            environment.put("CMAKE_ROOT", cmakeRoot);
        }
    }

    //Test results for each method
    private void printResults(String methodName, RuntimeOutputClassifier.Verdict verdict, ProcessOutput output) {
        MessageUtils consoleInstance = MessageUtils.getInstance(project);
        if (verdict.failed()) {
            hasRuntimeErrors = true;

            String deviceLimitation = verdict.deviceLimitation();

            if (deviceLimitation != null) {
                // The kernel was generated correctly; it simply uses a
                // capability this device lacks (e.g. FP64). Report it as a
                // warning rather than a scary exception/stack trace.
                consoleInstance.showWarnMsg("Kernel not supported on this device",
                        methodName + ": " + deviceLimitation);
            } else {
                // Genuine error (including OpenCL compiler exceptions): keep
                // the raw diagnostic so it is not mistaken for a clean run.
                consoleInstance.showErrorMsg(MessageBundle.message("dynamic.info.title"),
                        methodName + ": " + verdict.errorDetail());
            }

            // Show the generated kernel with error lines stripped out (for debugging)
            String cleanKernel = verdict.kernelSource();
            if (!cleanKernel.isBlank()) {
                consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.opencl"), cleanKernel);
            }

            consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.title"),
                    MessageBundle.message("dynamic.info.documentation"));
            // A device-capability limitation is not a bug, so don't prompt to report one.
            if (deviceLimitation == null) {
                consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.title"),
                        MessageBundle.message("dynamic.info.bug"));
            }
        } else {
            consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.title"),
                    methodName + ": " + MessageBundle.message("dynamic.info.noException") );
            consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.opencl"), output.getStdout());
        }
        completed = true;
    }

    private void showStatDialog(long runningTime){
        if (completed) {
            String title;
            NotificationType type;
            if (hasRuntimeErrors) {
                title = MessageBundle.message("dynamic.info.statistics.title.error");
                type = NotificationType.WARNING;
            } else {
                title = MessageBundle.message("dynamic.info.statistics.title");
                type = NotificationType.INFORMATION;
            }
            Notification notification = TornadoNotifications.create(title,
                    MessageBundle.message("dynamic.info.statistics.body") + " " + runningTime + "ms", type);
            notification.addAction(new ChangeParameterSize());
            ApplicationManager.getApplication().invokeLater(() -> Notifications.Bus.notify(notification, project));
        }
    }

    // Removes the temporary directory; never throws, so it cannot mask the original outcome
    private static void cleanUp(@Nullable Path workDir) {
        if (workDir != null && !FileUtil.delete(workDir.toFile())) {
            LOG.warn("Could not delete the dynamic inspection directory " + workDir);
        }
    }

    static class ChangeParameterSize extends NotificationAction {
        public ChangeParameterSize() {
            super(MessageBundle.message("dynamic.parameterSize.button"));
        }
        @Override
        public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), TornadoSettingConfiguration.class);
            notification.expire();
        }
    }
}
