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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.concurrency.annotations.RequiresEdt;
import uk.ac.manchester.beehive.tornado.plugins.ui.console.ConsoleWindowFactory;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Project service that owns the TornadoInsight console. The {@link ConsoleView}
 * is created lazily, registered as a child of this service (so it is disposed
 * with the project), and embedded by {@link ConsoleWindowFactory}.
 */
@Service(Service.Level.PROJECT)
public final class MessageUtils implements Disposable {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final Project project;
    private ConsoleView consoleView;
    private ToolWindow toolWindow;

    public MessageUtils(Project project) {
        this.project = project;
    }

    @NotNull
    public static MessageUtils getInstance(Project project) {
        return project.getService(MessageUtils.class);
    }

    public void showInfoMsg(String title, String body) {
        showConsole(() -> {
            printTitle(title, ConsoleViewContentType.NORMAL_OUTPUT);
            printBody(body, ConsoleViewContentType.NORMAL_OUTPUT);
            consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT);
        });
    }

    public void showWarnMsg(String title, String body) {
        showConsole(() -> {
            printTitle(title, ConsoleViewContentType.LOG_INFO_OUTPUT);
            printBody(body, ConsoleViewContentType.LOG_INFO_OUTPUT);
            consoleView.print("\n", ConsoleViewContentType.LOG_INFO_OUTPUT);
        });
    }

    public void showErrorMsg(String title, String body) {
        showConsole(() -> {
            printTitle(title, ConsoleViewContentType.ERROR_OUTPUT);
            printBody(body, ConsoleViewContentType.ERROR_OUTPUT);
            consoleView.print("\n", ConsoleViewContentType.ERROR_OUTPUT);
        });
    }

    private void printTitle(String title, ConsoleViewContentType contentType) {
        if (title.equals("info") || title.equals("warning") || title.equals("error")) {
            consoleView.print("> " + TIMESTAMP.format(LocalDateTime.now()) + "\n", contentType);
        } else {
            consoleView.print("> " + TIMESTAMP.format(LocalDateTime.now()) + "\t" + title + "\n", contentType);
        }
    }

    private void printBody(String body, ConsoleViewContentType contentType) {
        String[] bodys = body.split("\n");
        for (String s : bodys) {
                consoleView.print(s + "\n", contentType);
        }
    }

    /**
     * Returns the project's console view, creating it on first use.
     */
    @RequiresEdt
    public @NotNull ConsoleView getConsoleView() {
        if (consoleView == null) {
            consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            Disposer.register(this, consoleView);
        }
        return consoleView;
    }

    private void showConsole(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            if (toolWindow == null) {
                toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ConsoleWindowFactory.ID);
            }
            if (toolWindow == null) {
                return;
            }
            if (!toolWindow.isAvailable()) {
                toolWindow.setAvailable(true);
            }
            if (!toolWindow.isActive()) {
                toolWindow.activate(null);
            }
            getConsoleView().requestScrollingToEnd();
            runnable.run();
        });


    }

    @Override
    public void dispose() {
        // The console view is registered as a child disposable in getConsoleView()
    }
}
