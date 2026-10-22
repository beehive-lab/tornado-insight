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
import com.intellij.execution.util.ExecUtil;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.entity.EnvironmentVariable;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoJdkContract;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingConfiguration;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageUtils;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoNotifications;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ExecutionEngine {

    private static final Logger LOG = Logger.getInstance(ExecutionEngine.class);

    private final String tempFolderPath;

    private final HashMap<String, PsiMethod> fileMethodMap;

    private final Project project;

    private boolean completed;
    private boolean hasRuntimeErrors;

    public ExecutionEngine(Project project, String tempFolderPath, HashMap<String, PsiMethod> fileMethodMap) {
        this.project = project;
        this.tempFolderPath = tempFolderPath;
        this.fileMethodMap = fileMethodMap;
        this.completed = false;
        this.hasRuntimeErrors = false;
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

    public void run(){
        // Performing UI related operations on a non-EDT is not allowed.
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.start"));

        // Validate JDK before starting
        if (!validateProjectJdk()) {
            return;
        }

        // Validate TornadoVM SDK before starting
        if (!validateTornadoSdk()) {
            return;
        }

        // Make sure the configured test-data size will produce a meaningful run
        if (!validateParameterSize()) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // 'tornado --version' is a blocking subprocess call, so it must
            // run off the EDT (OSProcessHandler.waitFor refuses on the EDT).
            if (!validateJdkCompatibility()) {
                return;
            }

            long startTime = System.currentTimeMillis();
            ArrayList<String> files = new ArrayList<>(fileMethodMap.keySet());
            try {
                compile(tempFolderPath, files);
                packFolder(tempFolderPath, tempFolderPath);
                executeJars(tempFolderPath);
            }catch (UnsupportedOperationException ignore){}
            catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                long runningTime = System.currentTimeMillis() - startTime;
                showStatDialog(runningTime);
                cleanUp();
            }
        });
    }

    private boolean validateProjectJdk() {
        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();

        if (projectSdk == null) {
            Notification notification = TornadoNotifications.create(
                "No Project JDK Configured",
                "Please configure JDK 21 or newer in Project Structure (File > Project Structure > Project Settings > Project)",
                NotificationType.ERROR
            );
            notification.addAction(new NotificationAction("Open Project Structure") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                    ProjectSettingsService.getInstance(project).openProjectSettings();
                }
            });
            Notifications.Bus.notify(notification, project);
            return false;
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
            notification.addAction(new NotificationAction("Open Project Structure") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                    ProjectSettingsService.getInstance(project).openProjectSettings();
                }
            });
            Notifications.Bus.notify(notification, project);
            return false;
        }

        return true;
    }

    private boolean validateTornadoSdk() {
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
    private boolean validateParameterSize() {
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
    private static String describeProjectJdk(Sdk sdk, int feature) {
        String name = sdk.getName();
        String version = sdk.getVersionString();
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isBlank()) {
            sb.append(name.trim());
        }
        if (version != null && !version.isBlank() && !version.trim().equals(name)) {
            sb.append(sb.isEmpty() ? version.trim() : " (" + version.trim() + ")");
        }
        if (sb.isEmpty()) {
            sb.append("JDK ").append(feature);
        }
        if (isGraalVmDistribution(sdk)) {
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
    private boolean validateJdkCompatibility() {
        TornadoJdkContract contract = detectTornadoVmJdkContract();
        if (contract == null) {
            // detectTornadoVmJdkContract already surfaced a notification with details
            return false;
        }

        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
        // validateProjectJdk already guaranteed a non-null SDK >= 21
        JavaSdkVersion projectVersion = JavaSdkVersion.fromVersionString(projectSdk.getVersionString());
        int projectJdk = projectVersion != null
                ? projectVersion.getMaxLanguageLevel().toJavaVersion().feature
                : -1;

        MessageUtils.getInstance(project).showInfoMsg("Info",
                "TornadoVM requires " + contract.requirement() + " (from " + contract.source() + "); project JDK is "
                        + describeProjectJdk(projectSdk, projectJdk) + ".");

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
            notification.addAction(new NotificationAction("Open Project Structure") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                    ProjectSettingsService.getInstance(project).openProjectSettings();
                }
            });
            Notifications.Bus.notify(notification, project);
            return false;
        }

        if (contract.vendorsGraal() && isGraalVmDistribution(projectSdk)) {
            Notification notification = TornadoNotifications.create(
                "Incompatible JDK distribution for default TornadoVM",
                "The project JDK at " + projectSdk.getHomePath() + " is a GraalVM build. TornadoVM 6.0.0 and" +
                    " newer bundle their own Graal compiler and JVMCI, which collide with the modules a" +
                    " GraalVM JDK already ships and abort the JVM (fatal error: HotSpotNmethod.profileDeopt)." +
                    " Set the project JDK to a standard OpenJDK build (e.g. Eclipse Temurin or Oracle OpenJDK)," +
                    " " + contract.requirement() + ", in Project Structure.",
                NotificationType.ERROR
            );
            notification.addAction(new NotificationAction("Open Project Structure") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                    ProjectSettingsService.getInstance(project).openProjectSettings();
                }
            });
            Notifications.Bus.notify(notification, project);
            return false;
        }

        return true;
    }

    /**
     * Detects a GraalVM JDK (Community or Oracle) from the {@code release} file
     * in its home directory. TornadoVM &ge; 6.0.0 vendors its own Graal compiler
     * and, on JDK 22-26, patches {@code jdk.internal.vm.ci} with a frozen JDK-21
     * copy; a GraalVM JDK ships both with a different JVMCI ABI, so the patched-in
     * classes fail HotSpot's field-offset checks and the JVM aborts. Recognising
     * this lets us refuse with a clear message instead of a core dump.
     */
    private static boolean isGraalVmDistribution(Sdk sdk) {
        String home = sdk.getHomePath();
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
    private TornadoJdkContract detectTornadoVmJdkContract() {
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
        return probeTornadoJdkContractViaVersion();
    }

    /**
     * Fallback for SDKs without {@code etc/tornado.jdk}: runs
     * {@code tornado --version} and reads the target JDK from the
     * {@code version=<x>-jdk<n>} line (such SDKs are pinned to that single
     * release). If {@code tornado} itself rejected the running JDK, its stderr
     * names the one it wants, which is surfaced as the target instead. Returns
     * {@code null} (and surfaces a user-visible notification) when the
     * subprocess cannot be launched, fails, or yields no recognisable marker.
     */
    private TornadoJdkContract probeTornadoJdkContractViaVersion() {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        commandLine.setExePath(resolveTornadoExe());
        configureEnvironmentVariables(commandLine);
        commandLine.addParameter("--version");

        ProcessOutput output;
        try {
            output = ExecUtil.execAndGetOutput(commandLine);
        } catch (ExecutionException e) {
            notifyTornadoVersionFailure(
                    "Failed to launch 'tornado --version': " + e.getMessage()
                            + ". Verify that 'tornado' is on PATH and that TORNADOVM_HOME points at a valid installation.");
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

    private void compile(String outputDir, ArrayList<String> javaFiles) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.compile"));

        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (projectSdk == null) {
            throw new UnsupportedOperationException("No project JDK configured");
        }

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
            throw new UnsupportedOperationException("TornadoVM JARs not found");
        }

        String classpath = apiPath + File.pathSeparator + matricesPath + File.pathSeparator + unitTestPath;

        // Derive --release from the project SDK so it stays in sync with the JDK
        // actually used to compile (and so that --enable-preview, which requires
        // --release to match the current JDK, remains valid).
        JavaSdkVersion projectSdkVersion = JavaSdkVersion.fromVersionString(projectSdk.getVersionString());
        int releaseVersion = projectSdkVersion != null
                ? projectSdkVersion.getMaxLanguageLevel().toJavaVersion().feature
                : 21; // Fallback to the minimum supported release

        GeneralCommandLine commandLine = new GeneralCommandLine();
        String javacPath = projectSdk.getHomePath() + File.separator + "bin" + File.separator + "javac";
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
        commandLine.addParameter(outputDir);
        commandLine.addParameters(javaFiles);  // Adds each Java file to the command line

        // Execute the command
        try {
            ProcessOutput output = ExecUtil.execAndGetOutput(commandLine);
            int exitCode = output.getExitCode();
            String stderr = output.getStderr();
            if (exitCode != 0) {
                // Surface a concise, human-readable warning instead of dumping
                // the raw javac output. A type mismatch in the generated harness
                // (e.g. a kernel parameter whose type the device does not
                // support, such as FP64/double on a GPU without double support)
                // produces hundreds of lines of overload candidates and
                // type-variable declarations that bury the actual cause.
                LOG.warn("Generated code compilation failed (exit code " + exitCode + "):\n" + stderr);
                MessageUtils.getInstance(project).showWarnMsg("Generated code did not compile",
                        CompilerOutputSummarizer.summarize(stderr));
                throw new UnsupportedOperationException("Compilation failed with exit code " + exitCode);
            }
        } catch (ExecutionException e) {
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    MessageBundle.message("dynamic.error.compile"));
        }
    }

    private void packFolder(String classFolderPath, String outputFolderPath) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.packing"));
        File classFolder = new File(classFolderPath);
        File[] classFiles = classFolder.listFiles((dir, name) -> name.endsWith(".class"));
        if (classFiles == null) {
            return;
        }

        File outputFolder = new File(outputFolderPath);
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
        }

        for (File classFile : classFiles) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, classFile.getName().replace(".class", ""));

            File outputJar = new File(outputFolder, classFile.getName().replace(".class", ".jar"));

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar), manifest)) {
                Path classPath = classFile.toPath();
                jos.putNextEntry(new JarEntry(classPath.getFileName().toString()));
                Files.copy(classPath, jos);
                jos.closeEntry();
            } catch (IOException e) {
                MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                        MessageBundle.message("dynamic.error.packing"));
            }
        }
    }

    private void executeJars(String jarFolderPath) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.execution"));
        GeneralCommandLine commandLine = new GeneralCommandLine();
        //Detecting if the user has correctly installed TornadoVM
        commandLine.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE);
        // On Windows, explicitly use tornado.exe to avoid trying to execute the Unix shell script
        commandLine.setExePath(resolveTornadoExe());
        configureEnvironmentVariables(commandLine);
        commandLine.addParameter("--device");

        try {
            CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
            ProcessOutput output = handler.runProcess();
            if (output.getExitCode() != 0) {
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

        File folder = new File(jarFolderPath);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null) {
            System.out.println("No files found in the specified directory.");
            return;
        }

        for (File file : listOfFiles) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                runTornadoOnJar(file.getAbsolutePath());
            }
        }
    }

    private void runTornadoOnJar(String jarPath) {
        GeneralCommandLine commandLine = getGeneralCommandLine(jarPath);
        try {
            CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
            ProcessOutput output = handler.runProcess();
            // Cannot use the exit code alone to determine if TornadoVM hit an error.
            // Under normal circumstances TornadoVM output includes warnings like:
            // "WARNING: Using incubator modules: jdk.incubator.foreign, jdk.incubator.vector"
            // We must check for both Java exceptions and OpenCL/SPIR-V/PTX runtime errors.
            RuntimeOutputClassifier.Verdict verdict =
                    RuntimeOutputClassifier.classify(output.getStdout(), output.getStderr(), output.getExitCode());
            printResults(jarPath, verdict, output);
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
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
        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
        String javaHome = projectSdk != null ? projectSdk.getHomePath() : null;
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
    private void printResults(String jarPath, RuntimeOutputClassifier.Verdict verdict, ProcessOutput output) {
        String javaPath = jarPath.substring(0, jarPath.lastIndexOf(".jar")) + ".java";
        ApplicationManager.getApplication().runReadAction(() -> {
            String methodName = TornadoTWTask.psiMethodFormat(fileMethodMap.get(javaPath));
            if (verdict.failed()) {
                hasRuntimeErrors = true;
                MessageUtils consoleInstance = MessageUtils.getInstance(project);

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
                    String errorDetail = verdict.errorDetail();
                    consoleInstance.showErrorMsg(MessageBundle.message("dynamic.info.title"),
                            methodName + ": " + errorDetail);
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
                MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                        methodName + ": " + MessageBundle.message("dynamic.info.noException") );
                MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.opencl"), output.getStdout());
            }
            completed = true;
        });
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

    private void cleanUp(){
        File file = new File(tempFolderPath);
        try {
            FileUtils.deleteDirectory(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
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
