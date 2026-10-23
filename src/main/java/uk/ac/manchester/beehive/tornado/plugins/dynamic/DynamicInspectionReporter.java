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

package uk.ac.manchester.beehive.tornado.plugins.dynamic;

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
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.JavacRunner;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.Processes;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.TornadoRunner;
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
            notification.addAction(new NotificationAction(MessageBundle.message("dynamic.action.projectStructure")) {
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
        notifyError(MessageBundle.message("dynamic.error.noJdk.title"), MessageBundle.message("dynamic.error.noJdk.content"), true);
    }

    void projectJdkTooOld(@NotNull String current) {
        notifyError(MessageBundle.message("dynamic.error.jdkTooOld.title"), MessageBundle.message("dynamic.error.jdkTooOld.content", current), true);
    }

    void sdkDetected(@Nullable String homeValue) {
        console().showInfoMsg(MessageBundle.message("dynamic.info.infoTitle"),
                MessageBundle.message("dynamic.info.sdkDetected", homeValue != null ? homeValue : MessageBundle.message("dynamic.info.sdkNotSet")));
    }

    void sdkNotConfigured() {
        Notifications.Bus.notify(TornadoNotifications.sdkNotConfigured(NotificationType.ERROR), project);
    }

    void invalidParameterSize(int size, int maxSize) {
        String detail = size <= 0
                ? MessageBundle.message("dynamic.error.parameterSize.empty", String.valueOf(size))
                : MessageBundle.message("dynamic.error.parameterSize.tooLarge", String.valueOf(size), String.valueOf(maxSize));
        Notification notification = TornadoNotifications.create(
                MessageBundle.message("dynamic.error.parameterSize.title"),
                MessageBundle.message("dynamic.error.parameterSize.content", String.valueOf(maxSize), detail),
                NotificationType.ERROR
        );
        notification.addAction(new ChangeParameterSize());
        Notifications.Bus.notify(notification, project);
    }

    void jdkRequirement(@NotNull TornadoJdkContract contract, @NotNull String projectJdk) {
        console().showInfoMsg(MessageBundle.message("dynamic.info.infoTitle"),
                MessageBundle.message("dynamic.info.jdkRequirement", contract.requirement(), contract.source(), projectJdk));
    }

    void incompatibleJdk(int projectJdk, @Nullable String sdkHome, @NotNull TornadoJdkContract contract) {
        notifyError(MessageBundle.message("dynamic.error.incompatibleJdk.title"),
                MessageBundle.message("dynamic.error.incompatibleJdk.content",
                        String.valueOf(projectJdk), String.valueOf(sdkHome), contract.requirement()),
                true);
    }

    void graalVmJdk(@Nullable String jdkHome, @NotNull TornadoJdkContract contract) {
        notifyError(MessageBundle.message("dynamic.error.graalVm.title"),
                MessageBundle.message("dynamic.error.graalVm.content", String.valueOf(jdkHome), contract.requirement()),
                true);
    }

    void tornadoVersionFailure(@NotNull String detail) {
        notifyError(MessageBundle.message("dynamic.error.versionCheck.title"),
                MessageBundle.message("dynamic.error.versionCheck.content", detail.replace("\n", "<br>")),
                false);
    }

    // --- pipeline stages

    // A task in the panel whose method (or file) was deleted or changed beyond
    // recognition since the last refresh
    void staleTask(@NotNull String task) {
        console().showWarnMsg(title(), MessageBundle.message("dynamic.warn.staleTask", task));
    }

    void unsupportedParameter(@NotNull String task, @NotNull String message) {
        console().showWarnMsg(title(), MessageBundle.message("dynamic.warn.unsupportedParameter", task, message));
    }

    void cancelled() {
        console().showWarnMsg(title(), MessageBundle.message("dynamic.warn.cancelled"));
    }

    void failed(@NotNull String message) {
        console().showErrorMsg(title(), MessageBundle.message("dynamic.error.failed", message));
    }

    void failedUnexpectedly(@NotNull Throwable error) {
        console().showErrorMsg(title(), MessageBundle.message("dynamic.error.failedUnexpectedly", String.valueOf(error)));
    }

    void debugCopyFailed(@NotNull String target, @NotNull String message) {
        console().showWarnMsg(title(), MessageBundle.message("dynamic.warn.debugCopyFailed", target, message));
    }

    void compiling() {
        console().showInfoMsg(title(), MessageBundle.message("dynamic.info.compile"));
    }

    void missingJars(@NotNull String missingJars) {
        console().showErrorMsg(MessageBundle.message("dynamic.error.missingJars.title"), MessageBundle.message("dynamic.error.missingJars", missingJars));
    }

    void compileFailed(@NotNull JavacRunner.Result result) {
        switch (result.status()) {
            case LAUNCH_FAILED -> console().showErrorMsg(title(),
                    MessageBundle.message("dynamic.error.compile") + "\n" + result.detail());
            case TIMED_OUT -> console().showErrorMsg(title(),
                    MessageBundle.message("dynamic.error.compileTimeout", Processes.describeTimeout(JavacRunner.TIMEOUT_MS)));
            // A concise, human-readable summary instead of the raw javac output: a type
            // mismatch in the generated harness produces hundreds of lines of overload
            // candidates and type-variable declarations that bury the actual cause.
            case ERRORS -> console().showWarnMsg(MessageBundle.message("dynamic.warn.compileErrors.title"),
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
        Notification notification = TornadoNotifications.create(MessageBundle.message("dynamic.error.tornadoNotDetected.title"),
                MessageBundle.message("dynamic.error.tornadoNotDetected.content"), NotificationType.ERROR);
        notification.addAction(new NotificationAction(MessageBundle.message("dynamic.action.installTornado")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                BrowserUtil.browse("https://tornadovm.readthedocs.io/en/latest/installation.html#");
            }
        });
        Notifications.Bus.notify(notification, project);
    }

    void tornadoLaunchFailed(@NotNull String message) {
        console().showErrorMsg(title(), MessageBundle.message("dynamic.error.tornadoLaunch", message));
    }

    void runLaunchFailed(@NotNull String task, @NotNull String message) {
        console().showErrorMsg(title(), MessageBundle.message("dynamic.error.runLaunch", task, message));
    }

    void runTimedOut(@NotNull String task) {
        console().showErrorMsg(title(),
                MessageBundle.message("dynamic.error.runTimeout", task, Processes.describeTimeout(TornadoRunner.RUN_TIMEOUT_MS)));
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
            console.showWarnMsg(MessageBundle.message("dynamic.warn.deviceLimitation.title"), task + ": " + deviceLimitation);
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
