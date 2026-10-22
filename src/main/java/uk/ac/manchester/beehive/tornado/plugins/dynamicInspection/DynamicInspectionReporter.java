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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.run.JavacRunner;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.run.Processes;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.run.TornadoRunner;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoJdkContract;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingConfiguration;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageUtils;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoNotifications;

/**
 * Reports the progress and the results of dynamic inspection on the TornadoInsight
 * console and in notifications. Safe to call from any thread.
 */
final class DynamicInspectionReporter {

    private final Project project;

    DynamicInspectionReporter(@NotNull Project project) {
        this.project = project;
    }

    private MessageUtils console() {
        return MessageUtils.getInstance(project);
    }

    private static String title() {
        return MessageBundle.message("dynamic.info.title");
    }

    private void notifyError(String title, String content, boolean projectStructureAction) {
        Notification notification = TornadoNotifications.create(title, content, NotificationType.ERROR);
        if (projectStructureAction) {
            notification.addAction(new NotificationAction("Open Project Structure") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                    ProjectSettingsService.getInstance(project).openProjectSettings();
                }
            });
        }
        Notifications.Bus.notify(notification, project);
    }

    // --- preflight checks

    void started() {
        console().showInfoMsg(title(), MessageBundle.message("dynamic.info.start"));
    }

    void noProjectJdk() {
        notifyError("No Project JDK Configured",
                "Please configure JDK 21 or newer in Project Structure (File > Project Structure > Project Settings > Project)",
                true);
    }

    void projectJdkTooOld(@NotNull String current) {
        notifyError("Incompatible JDK Version",
                "TornadoVM requires JDK 21 or newer. Current project JDK: " + current
                        + ". Please update your project JDK in Project Structure.",
                true);
    }

    void sdkDetected(@Nullable String homeValue) {
        console().showInfoMsg("Info", "TORNADOVM_HOME detected: " + (homeValue != null ? homeValue : "NOT SET"));
    }

    void sdkNotConfigured() {
        notifyError("TornadoVM SDK Not Configured",
                "<html>TORNADOVM_HOME environment variable is not set.<br><br>" +
                        "Please:<br>" +
                        "1. Download and install TornadoVM from <a href='https://www.tornadovm.org/downloads'>tornadovm.org/downloads</a><br>" +
                        "2. Set the TORNADOVM_HOME environment variable<br>" +
                        "3. Restart your IntelliJ session</html>",
                false);
    }

    void invalidParameterSize(int size, int maxSize) {
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
    }

    void jdkRequirement(@NotNull TornadoJdkContract contract, @NotNull String projectJdk) {
        console().showInfoMsg("Info",
                "TornadoVM requires " + contract.requirement() + " (from " + contract.source() + "); project JDK is "
                        + projectJdk + ".");
    }

    void incompatibleJdk(int projectJdk, @Nullable String sdkHome, @NotNull TornadoJdkContract contract) {
        notifyError("Incompatible JDK for default TornadoVM",
                "Current project JDK " + projectJdk +
                        " is not compatible with the default TornadoVM at " + sdkHome +
                        ", which requires " + contract.requirement() + "." +
                        " Either change the project JDK in Project Structure, or point TORNADOVM_HOME at a" +
                        " TornadoVM build that supports JDK " + projectJdk + ".",
                true);
    }

    void graalVmJdk(@Nullable String jdkHome, @NotNull TornadoJdkContract contract) {
        notifyError("Incompatible JDK distribution for default TornadoVM",
                "The project JDK at " + jdkHome + " is a GraalVM build. TornadoVM 6.0.0 and" +
                        " newer bundle their own Graal compiler and JVMCI, which collide with the modules a" +
                        " GraalVM JDK already ships and abort the JVM (fatal error: HotSpotNmethod.profileDeopt)." +
                        " Set the project JDK to a standard OpenJDK build (e.g. Eclipse Temurin or Oracle OpenJDK)," +
                        " " + contract.requirement() + ", in Project Structure.",
                true);
    }

    void tornadoVersionFailure(@NotNull String detail) {
        notifyError("TornadoVM installation check failed",
                "<html>Could not determine the JDK that TornadoVM was built against.<br><br>" +
                        detail.replace("\n", "<br>") + "</html>",
                false);
    }

    // --- pipeline stages

    // A task in the panel whose method (or file) was deleted or changed beyond
    // recognition since the last refresh
    void staleTask(@NotNull String task) {
        console().showWarnMsg(title(),
                "Task " + task + " no longer exists in the source. Refresh the TornadoVM panel and try again.");
    }

    void cancelled() {
        console().showWarnMsg(title(), "Dynamic inspection was cancelled.");
    }

    void failed(@NotNull String message) {
        console().showErrorMsg(title(), "Dynamic inspection failed: " + message);
    }

    void failedUnexpectedly(@NotNull Throwable error) {
        console().showErrorMsg(title(), "Dynamic inspection failed unexpectedly: " + error);
    }

    void debugCopyFailed(@NotNull String target, @NotNull String message) {
        console().showWarnMsg(title(), "Could not save the generated test code to " + target + ": " + message);
    }

    void compiling() {
        console().showInfoMsg(title(), MessageBundle.message("dynamic.info.compile"));
    }

    void missingJars(@NotNull String missingJars) {
        String errorMsg = "Could not find required TornadoVM JARs in $TORNADOVM_HOME/share/java/tornado/\n" +
                "Missing: " + missingJars + "\n\n" +
                "Please check that:\n" +
                "1. TORNADOVM_HOME environment variable points to the correct TornadoVM SDK directory\n" +
                "2. The directory contains share/java/tornado/ with the required JAR files\n\n" +
                "For installation instructions, visit: https://www.tornadovm.org/downloads";
        console().showErrorMsg("TornadoVM JARs Not Found", errorMsg);
    }

    void compileFailed(@NotNull JavacRunner.Result result) {
        switch (result.status()) {
            case LAUNCH_FAILED -> console().showErrorMsg(title(),
                    MessageBundle.message("dynamic.error.compile") + "\n" + result.detail());
            case TIMED_OUT -> console().showErrorMsg(title(),
                    "Compiling the generated test code did not finish within "
                            + Processes.describeTimeout(JavacRunner.TIMEOUT_MS) + " and was stopped.");
            // A concise, human-readable summary instead of the raw javac output: a type
            // mismatch in the generated harness produces hundreds of lines of overload
            // candidates and type-variable declarations that bury the actual cause.
            case ERRORS -> console().showWarnMsg("Generated code did not compile",
                    CompilerOutputSummarizer.summarize(result.detail()));
            case OK -> {
            }
        }
    }

    void packing() {
        console().showInfoMsg(title(), MessageBundle.message("dynamic.info.packing"));
    }

    void packingFailed(@NotNull String message) {
        console().showErrorMsg(title(), MessageBundle.message("dynamic.error.packing") + ": " + message);
    }

    void executing() {
        console().showInfoMsg(title(), MessageBundle.message("dynamic.info.execution"));
    }

    void tornadoNotDetected() {
        Notification notification = TornadoNotifications.create("TornadoVM not detected",
                "TornadoVM is not properly installed or configured", NotificationType.ERROR);
        notification.addAction(new NotificationAction("How to install and configure TornadoVM") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                BrowserUtil.browse("https://tornadovm.readthedocs.io/en/latest/installation.html#");
            }
        });
        Notifications.Bus.notify(notification, project);
    }

    void tornadoLaunchFailed(@NotNull String message) {
        console().showErrorMsg(title(),
                "TornadoVM is not properly installed or configured.\n\n" +
                        "Error: " + message);
    }

    void runLaunchFailed(@NotNull String task, @NotNull String message) {
        console().showErrorMsg(title(), task + ": could not run TornadoVM: " + message);
    }

    void runTimedOut(@NotNull String task) {
        console().showErrorMsg(title(), task + ": TornadoVM did not finish within "
                + Processes.describeTimeout(TornadoRunner.RUN_TIMEOUT_MS) + "; the run was stopped.");
    }

    /**
     * Reports the result of running one task.
     */
    void result(@NotNull String task, @NotNull RuntimeOutputClassifier.Verdict verdict, @NotNull String stdout) {
        MessageUtils console = console();
        if (!verdict.failed()) {
            console.showInfoMsg(title(), task + ": " + MessageBundle.message("dynamic.info.noException"));
            console.showInfoMsg(MessageBundle.message("dynamic.info.opencl"), stdout);
            return;
        }

        String deviceLimitation = verdict.deviceLimitation();
        if (deviceLimitation != null) {
            // The kernel was generated correctly; it simply uses a
            // capability this device lacks (e.g. FP64). Report it as a
            // warning rather than a scary exception/stack trace.
            console.showWarnMsg("Kernel not supported on this device", task + ": " + deviceLimitation);
        } else {
            // Genuine error (including OpenCL compiler exceptions): keep
            // the raw diagnostic so it is not mistaken for a clean run.
            console.showErrorMsg(title(), task + ": " + verdict.errorDetail());
        }

        // Show the generated kernel with error lines stripped out (for debugging)
        if (!verdict.kernelSource().isBlank()) {
            console.showInfoMsg(MessageBundle.message("dynamic.info.opencl"), verdict.kernelSource());
        }

        console.showInfoMsg(title(), MessageBundle.message("dynamic.info.documentation"));
        // A device-capability limitation is not a bug, so don't prompt to report one.
        if (deviceLimitation == null) {
            console.showInfoMsg(title(), MessageBundle.message("dynamic.info.bug"));
        }
    }

    void statistics(boolean hasRuntimeErrors, long runningTimeMs) {
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
                MessageBundle.message("dynamic.info.statistics.body") + " " + runningTimeMs + "ms", type);
        notification.addAction(new ChangeParameterSize());
        ApplicationManager.getApplication().invokeLater(() -> Notifications.Bus.notify(notification, project));
    }

    static class ChangeParameterSize extends NotificationAction {
        ChangeParameterSize() {
            super(MessageBundle.message("dynamic.parameterSize.button"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), TornadoSettingConfiguration.class);
            notification.expire();
        }
    }
}
