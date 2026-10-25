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

package uk.ac.manchester.beehive.tornado.plugins.dynamic;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.CodeGenerator.Batch;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.CodeGenerator.Harness;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.UnsupportedParameterTypeException;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.JarPacker;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.JavacRunner;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.TornadoRunner;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoJdkContract;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;
import uk.ac.manchester.beehive.tornado.plugins.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow.TornadoTaskService.TaskEntry;

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
 * reports its own expected failures through the {@link DynamicInspectionReporter}
 * and tells the pipeline whether to go on; nothing is rethrown to the IDE. The
 * processes are built and run by {@link JavacRunner} and {@link TornadoRunner}.
 * </p>
 */
public final class DynamicInspectionPipeline {

    private static final Logger LOG = Logger.getInstance(DynamicInspectionPipeline.class);

    /**
     * The project JDK, copied on the UI thread so the background task does not
     * query the project model.
     *
     * @param name          the SDK name, may be {@code null}
     * @param homePath      the JDK home, may be {@code null}
     * @param versionString the SDK version string, may be {@code null}
     * @param feature       the JDK feature version
     */
    record ProjectJdk(@Nullable String name, @Nullable String homePath, @Nullable String versionString, int feature) {
    }

    private final Project project;
    private final DynamicInspectionReporter reporter;
    private final TornadoSdk sdk;
    private final ProjectJdk jdk;
    private final List<TaskEntry> tasks;
    private final int parameterSize;
    private final boolean saveFileEnabled;
    private final String debugFileSaveLocation;

    private boolean completed;
    private boolean hasRuntimeErrors;

    private DynamicInspectionPipeline(Project project, DynamicInspectionReporter reporter, TornadoSdk sdk, ProjectJdk jdk,
                                      List<TaskEntry> tasks, int parameterSize, boolean saveFileEnabled,
                                      String debugFileSaveLocation) {
        this.project = project;
        this.reporter = reporter;
        this.sdk = sdk;
        this.jdk = jdk;
        this.tasks = List.copyOf(tasks);
        this.parameterSize = parameterSize;
        this.saveFileEnabled = saveFileEnabled;
        this.debugFileSaveLocation = debugFileSaveLocation;
    }

    /**
     * Starts dynamic inspection of {@code tasks}. Called on the UI thread.
     */
    public static void start(@NotNull Project project, @NotNull List<TaskEntry> tasks) {
        DynamicInspectionReporter reporter = new DynamicInspectionReporter(project);
        reporter.started();

        // Validate JDK before starting
        ProjectJdk jdk = validateProjectJdk(project, reporter);
        if (jdk == null) {
            return;
        }

        // Validate TornadoVM SDK before starting
        TornadoSdk sdk = TornadoSdk.getInstance();
        reporter.sdkDetected(sdk.homeValue());
        if (!sdk.isConfigured()) {
            LOG.warn("TORNADOVM_HOME is not set or empty");
            reporter.sdkNotConfigured();
            return;
        }

        // Make sure the configured test-data size will produce a meaningful run
        if (!validateParameterSize(reporter)) {
            return;
        }

        TornadoSettingState settings = TornadoSettingState.getInstance();
        DynamicInspectionPipeline pipeline = new DynamicInspectionPipeline(project, reporter, sdk, jdk, tasks,
                settings.parameterSize, settings.saveFileEnabled, settings.debugFileSaveLocation);
        new Task.Backgroundable(project, MessageBundle.message("dynamic.progress.title"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                pipeline.run(indicator);
            }

            @Override
            public void onCancel() {
                reporter.cancelled();
            }
        }.queue();
    }

    private static @Nullable ProjectJdk validateProjectJdk(Project project, DynamicInspectionReporter reporter) {
        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (projectSdk == null) {
            reporter.noProjectJdk();
            return null;
        }
        JavaSdkVersion sdkVersion = JavaSdkVersion.fromVersionString(projectSdk.getVersionString());
        if (sdkVersion == null || sdkVersion.compareTo(JavaSdkVersion.JDK_21) < 0) {
            reporter.projectJdkTooOld(sdkVersion != null ? sdkVersion.getDescription() : projectSdk.getVersionString());
            return null;
        }
        return new ProjectJdk(projectSdk.getName(), projectSdk.getHomePath(), projectSdk.getVersionString(),
                sdkVersion.getMaxLanguageLevel().toJavaVersion().feature);
    }

    /**
     * Refuses to launch dynamic inspection when the configured test-data size
     * is non-positive (or absurdly large). The harness generator pipes this
     * value into every synthetic allocator it emits (arrays, matrices, images,
     * vectors), so a zero would generate empty inputs that compile and "run"
     * cleanly without actually exercising the kernel - a misleading green
     * tick. The Settings dialog validates this on Apply, but nothing else
     * does, so a fresh install or a hand-edited state file can still leak a
     * bad value through to the runtime path.
     */
    private static boolean validateParameterSize(DynamicInspectionReporter reporter) {
        int size = TornadoSettingState.getInstance().parameterSize;
        int maxSize = TornadoSettingState.MAX_PARAMETER_SIZE - 1;
        if (size > 0 && size <= maxSize) {
            return true;
        }
        reporter.invalidParameterSize(size, maxSize);
        return false;
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
            indicator.setText(MessageBundle.message("dynamic.progress.checkInstallation"));
            if (!validateJdkCompatibility(indicator)) {
                return;
            }

            indicator.checkCanceled();
            indicator.setText(MessageBundle.message("dynamic.progress.generating"));
            Batch batch = ReadAction.nonBlocking(() -> CodeGenerator.collect(tasks))
                    .inSmartMode(project)
                    .expireWhen(project::isDisposed)
                    .wrapProgress(indicator)
                    .executeSynchronously();
            for (String stale : batch.staleTasks()) {
                reporter.staleTask(stale);
            }
            List<Harness> harnesses = new ArrayList<>();
            for (CodeGenerator.TaskModel task : batch.tasks()) {
                try {
                    harnesses.add(CodeGenerator.render(task, parameterSize));
                } catch (UnsupportedParameterTypeException e) {
                    reporter.unsupportedParameter(task.displayName(), e.getMessage());
                }
            }
            if (harnesses.isEmpty()) {
                return;
            }

            workDir = FileUtil.createTempDirectory("tornado-insight", null, false).toPath();
            Path sourceDir = Files.createDirectory(workDir.resolve("src"));
            Path classDir = Files.createDirectory(workDir.resolve("classes"));
            Path jarDir = Files.createDirectory(workDir.resolve("jars"));
            // tornado puts its working directory on the module path, so run it in an empty one
            TornadoRunner tornado = new TornadoRunner(sdk, jdk.homePath(), Files.createDirectory(workDir.resolve("run")));

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
            executeJars(indicator, tornado, jars);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (IOException e) {
            LOG.warn("Dynamic inspection failed", e);
            reporter.failed(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            if (e instanceof ControlFlowException) {
                throw e;
            }
            LOG.error("Dynamic inspection failed unexpectedly", e);
            reporter.failedUnexpectedly(e);
        } finally {
            if (completed) {
                reporter.statistics(hasRuntimeErrors, System.currentTimeMillis() - startTime);
            }
            cleanUp(workDir);
        }
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
        TornadoSdk.JdkContractResult result = sdk.jdkContract(indicator, jdk.homePath(), TornadoRunner.PROBE_TIMEOUT_MS);
        TornadoJdkContract contract = result.contract();
        if (contract == null) {
            LOG.warn("tornado --version check failed: " + result.failure());
            reporter.tornadoVersionFailure(String.valueOf(result.failure()));
            return false;
        }

        int projectJdk = jdk.feature();
        reporter.jdkRequirement(contract, describeProjectJdk(jdk));

        if (!contract.accepts(projectJdk)) {
            reporter.incompatibleJdk(projectJdk, sdk.homeValue(), contract);
            return false;
        }
        if (contract.vendorsGraal() && isGraalVmDistribution(jdk.homePath())) {
            reporter.graalVmJdk(jdk.homePath(), contract);
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
            reporter.debugCopyFailed(target.toString(), String.valueOf(e.getMessage()));
        }
    }

    /**
     * Compiles the harnesses into {@code outputDir}. A failure is reported (with
     * the summarised javac errors) and stops the pipeline.
     *
     * @return whether compilation succeeded
     */
    private boolean compile(ProgressIndicator indicator, Path outputDir, List<String> javaFiles) {
        reporter.compiling();

        String apiPath = sdk.apiJar();
        String matricesPath = sdk.matricesJar();
        String unitTestPath = sdk.unitTestsJar();
        String missingJars = JavacRunner.missingJars(apiPath, matricesPath, unitTestPath);
        if (!missingJars.isEmpty()) {
            reporter.missingJars(missingJars);
            return false;
        }

        JavacRunner.Result result = JavacRunner.run(JavacRunner.commandLine(String.valueOf(jdk.homePath()),
                JavacRunner.release(jdk.feature()), List.of(apiPath, matricesPath, unitTestPath), outputDir, javaFiles,
                sdk.isWindows()), indicator);
        switch (result.status()) {
            case OK -> {
                return true;
            }
            case LAUNCH_FAILED -> LOG.warn("Could not run javac: " + result.detail());
            case TIMED_OUT -> LOG.warn("javac timed out");
            case ERRORS -> LOG.warn("Generated code compilation failed (exit code " + result.exitCode() + "):\n"
                    + result.detail());
        }
        reporter.compileFailed(result);
        return false;
    }

    /**
     * Packs each compiled harness, with its nested and anonymous classes, into its
     * own executable jar.
     *
     * @return the jar of each harness, or {@code null} if packing failed (reported)
     */
    private @Nullable Map<Harness, Path> packJars(List<Harness> harnesses, Path classDir, Path jarDir) {
        reporter.packing();
        Map<Harness, Path> jars = new LinkedHashMap<>();
        for (Harness harness : harnesses) {
            Path jar = jarDir.resolve(harness.className() + ".jar");
            try {
                JarPacker.pack(classDir, harness.className(), jar);
            } catch (IOException e) {
                LOG.warn("Could not pack " + harness.className(), e);
                reporter.packingFailed(String.valueOf(e.getMessage()));
                return null;
            }
            jars.put(harness, jar);
        }
        return jars;
    }

    private void executeJars(ProgressIndicator indicator, TornadoRunner tornado, Map<Harness, Path> jars) {
        reporter.executing();
        // Detecting if the user has correctly installed TornadoVM
        try {
            ProcessOutput output = TornadoRunner.run(tornado.deviceCheckCommand(), indicator, TornadoRunner.PROBE_TIMEOUT_MS);
            if (output.isTimeout() || output.getExitCode() != 0) {
                reporter.tornadoNotDetected();
                return;
            }
        } catch (ExecutionException e) {
            reporter.tornadoLaunchFailed(String.valueOf(e.getMessage()));
            return;
        }

        int done = 0;
        indicator.setIndeterminate(false);
        for (Map.Entry<Harness, Path> entry : jars.entrySet()) {
            indicator.checkCanceled();
            indicator.setText2(entry.getKey().displayName());
            indicator.setFraction((double) done++ / jars.size());
            runHarness(indicator, tornado, entry.getKey(), entry.getValue());
        }
    }

    private void runHarness(ProgressIndicator indicator, TornadoRunner tornado, Harness harness, Path jar) {
        ProcessOutput output;
        try {
            output = TornadoRunner.run(tornado.runCommand(jar), indicator, TornadoRunner.RUN_TIMEOUT_MS);
        } catch (ExecutionException e) {
            LOG.warn("Could not run tornado on " + jar, e);
            reporter.runLaunchFailed(harness.displayName(), String.valueOf(e.getMessage()));
            return;
        }
        completed = true;
        if (output.isTimeout()) {
            hasRuntimeErrors = true;
            reporter.runTimedOut(harness.displayName());
            return;
        }
        // The exit code alone does not tell whether TornadoVM hit an error (JNI
        // errors do not end the process), so the output is classified as well.
        RuntimeOutputClassifier.Verdict verdict =
                RuntimeOutputClassifier.classify(output.getStdout(), output.getStderr(), output.getExitCode());
        hasRuntimeErrors |= verdict.failed();
        reporter.result(harness.displayName(), verdict, output.getStdout());
    }

    // Removes the temporary directory; never throws, so it cannot mask the original outcome
    private static void cleanUp(@Nullable Path workDir) {
        if (workDir != null && !FileUtil.delete(workDir.toFile())) {
            LOG.warn("Could not delete the dynamic inspection directory " + workDir);
        }
    }
}
