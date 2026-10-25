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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import org.jetbrains.annotations.NotNull;

/**
 * Creates notifications in the plugin's registered "TornadoInsight" notification
 * group (see plugin.xml), so they show up under that name in Settings | Notifications.
 */
public final class TornadoNotifications {

    public static final String GROUP_ID = "TornadoInsight";

    public static final String DOWNLOADS_URL = "https://www.tornadovm.org/downloads";

    private TornadoNotifications() {
    }

    /**
     * Creates (but does not show) a notification in the TornadoInsight group.
     * Callers add actions and post it with {@code notification.notify(project)}
     * or {@code Notifications.Bus.notify(notification, project)}.
     */
    public static @NotNull Notification create(@NotNull String title, @NotNull String content, @NotNull NotificationType type) {
        return NotificationGroupManager.getInstance()
                .getNotificationGroup(GROUP_ID)
                .createNotification(title, content, type);
    }

    /**
     * Creates (but does not show) the "TORNADOVM_HOME is not set" notification, with an
     * action that opens the TornadoVM downloads page. Notification content cannot carry
     * clickable links without the deprecated listener API, so the page is opened from
     * the action instead.
     */
    public static @NotNull Notification sdkNotConfigured(@NotNull NotificationType type) {
        Notification notification = create(MessageBundle.message("notification.sdkNotConfigured.title"),
                MessageBundle.message("notification.sdkNotConfigured.content"), type);
        notification.addAction(NotificationAction.createSimpleExpiring(
                MessageBundle.message("notification.action.installationInstructions"),
                () -> BrowserUtil.browse(DOWNLOADS_URL)));
        return notification;
    }
}
