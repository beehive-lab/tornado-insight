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

/**
 * Provides a custom console output tool window for the TornadoVM.
 * <p>
 * This console window allows developers to view specific logs or outputs related to dynamic inspections.
 * </p>
 */
public class ConsoleOutputToolWindow implements ToolWindowFactory {

    // Cache of console views associated with each project
    private static final Map<Project, ConsoleView> consoleViews = new HashMap<>();

    public static ToolWindow toolWindow;

    // Identifier for the TornadoVM Console tool window
    public static final String ID = "TornadoVM Console";

    /**
     * Creates the tool window content.
     *
     * @param project     the current project
     * @param toolWindow  the tool window to be filled with content
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        if (consoleViews.get(project) == null) {
            createToolWindow(project, toolWindow);
        }
        ConsoleOutputToolWindow.toolWindow = toolWindow;
    }

    /**
     * Retrieves the console view for a specific project. This allows for easy access to the
     * console view elsewhere in the plugin to print logs or other outputs.
     *
     * @param project the current project
     * @return the console view associated with the project
     */
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

    /**
     * Sets up the user interface for the console window view.
     *
     * @param project    the current project
     * @param toolWindow the tool window in which the console view is placed
     */
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

    /**
     * Retrieves the TornadoVM Console tool window for a specific project.
     *
     * @param project the current project
     * @return the TornadoVM Console tool window
     */
    public static ToolWindow getToolWindow(@NotNull Project project) {
        return ToolWindowManager.getInstance(project).getToolWindow(ID);
    }
}


