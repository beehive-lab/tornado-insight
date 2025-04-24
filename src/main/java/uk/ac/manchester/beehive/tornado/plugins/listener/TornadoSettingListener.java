/*
 * Copyright (c) 2023, 2025, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.listener;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.entity.EnvironmentVariable;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Initializes TornadoVM settings on project open.
 * Migrated from StartupActivity to ProjectActivity.
 */
public class TornadoSettingListener implements ProjectActivity {

    @Override
    public @NotNull CompletableFuture<Void> execute(@NotNull Project project, @NotNull Continuation<? super kotlin.Unit> continuation) {
        TornadoSettingState settingState = TornadoSettingState.getInstance();

        if (settingState.TornadoRoot == null) {
            settingState.isValid = false;
            Notification notification = new Notification(
                    "Print", "TornadoVM",
                    "Please configure the TornadoVM environment variable file",
                    NotificationType.INFORMATION
            );
            notification.addAction(new OpenTornadoSettingAction());
            Notifications.Bus.notify(notification, project);
        } else {
            settingState.isValid = true;
            try {
                EnvironmentVariable.parseFile(settingState.TornadoRoot + "/setvars.sh");
            } catch (IOException e) {
                throw new RuntimeException("Failed to load TornadoVM environment variables", e);
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Action to open TornadoVM plugin settings from the notification.
     */
    static class OpenTornadoSettingAction extends NotificationAction {
        public OpenTornadoSettingAction() {
            super("Configure TornadoVM");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), "TornadoVM");
            notification.expire();
        }
    }
}
