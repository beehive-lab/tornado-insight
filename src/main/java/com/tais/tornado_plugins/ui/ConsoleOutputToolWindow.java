package com.tais.tornado_plugins.ui;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
public class ConsoleOutputToolWindow implements ToolWindowFactory {

    private static final Map<Project, ConsoleView> consoleViews = new HashMap<>();

    public static ToolWindow toolWindow;

    public static final String ID = "TornadoVM Console";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        if (consoleViews.get(project) == null) {
            createToolWindow(project, toolWindow);
        }
        ConsoleOutputToolWindow.toolWindow = toolWindow;
    }

    // To make it easier to use the ConsoleView elsewhere, define this method to get the ConsoleView.
    // The ConsoleView object outputs text to a custom console window via its print method.
    public static ConsoleView getConsoleView(Project project) {
        // Once created, don't create it again.
        if (consoleViews.get(project) == null) {
            ToolWindow toolWindow = getToolWindow(project);
            createToolWindow(project, toolWindow);
        }
        return consoleViews.get(project);
    }

    // Setting the UI to the console window view
    private static void createToolWindow(Project project, ToolWindow toolWindow) {
        ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        consoleViews.put(project, consoleView);
        Content content = toolWindow.getContentManager().getFactory().
                createContent(consoleView.getComponent(), "Output", false);
        toolWindow.getContentManager().addContent(content);
        content.getComponent().setVisible(true);
        content.setCloseable(true);
        toolWindow.getContentManager().addContent(content);
    }

    public static ToolWindow getToolWindow(@NotNull Project project) {
        return ToolWindowManager.getInstance(project).getToolWindow(ID);
    }
}


