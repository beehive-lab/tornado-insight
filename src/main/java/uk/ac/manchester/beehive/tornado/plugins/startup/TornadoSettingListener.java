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

package uk.ac.manchester.beehive.tornado.plugins.startup;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoNotifications;

import java.util.concurrent.CompletableFuture;

/**
 * Initializes TornadoVM settings on project open.
 * Migrated from StartupActivity to ProjectActivity.
 */
public class TornadoSettingListener implements ProjectActivity {

    @Override
    public @NotNull CompletableFuture<Void> execute(@NotNull Project project, @NotNull Continuation<? super kotlin.Unit> continuation) {
        // Show warning notification when TORNADOVM_HOME is not set
        if (!TornadoSdk.getInstance().isConfigured()) {
            Notification notification = TornadoNotifications.sdkNotConfigured(NotificationType.WARNING);
            Notifications.Bus.notify(notification, project);
        }

        return CompletableFuture.completedFuture(null);
    }
}
