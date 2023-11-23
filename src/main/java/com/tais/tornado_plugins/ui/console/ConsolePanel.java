package com.tais.tornado_plugins.ui.console;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.tais.tornado_plugins.util.DataKeys;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ConsolePanel extends SimpleToolWindowPanel implements DataProvider {
    private final ConsoleView consoleView;

    public ConsolePanel(ToolWindow toolWindow, Project project) {
        super(Boolean.FALSE, Boolean.TRUE);
        this.consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        SimpleToolWindowPanel toolWindowPanel = new SimpleToolWindowPanel(Boolean.FALSE, Boolean.TRUE);
        toolWindowPanel.setContent(consoleView.getComponent());
        setContent(toolWindowPanel);
        final DefaultActionGroup consoleGroup = new DefaultActionGroup(consoleView.createConsoleActions());
        ActionToolbar consoleToolbar = ActionManager.getInstance().createActionToolbar("ConsoleToolbar", consoleGroup, true);
        consoleToolbar.setTargetComponent(toolWindowPanel);
        setToolbar(consoleToolbar.getComponent());
    }

    public void dispose() {
        if (consoleView != null) {
            Disposer.dispose(consoleView);
        }
    }

    @Override
    public @Nullable Object getData(@NotNull @NonNls String dataId) {
        if (DataKeys.TORNADO_CONSOLE_VIEW.is(dataId)){
            return consoleView;
        }
        return super.getData(dataId);
    }
}
