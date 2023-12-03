package uk.ac.manchester.beehive.tornado.plugins.ui.console;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;


public class ConsoleWindowFactory implements ToolWindowFactory, DumbAware {

        public static String ID = "TornadoInsight Console";

        @Override
        public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
            ConsolePanel consolePanel = new ConsolePanel(toolWindow, project);
            Content content = toolWindow.getContentManager().getFactory().createContent(consolePanel, "", true);
            toolWindow.getContentManager().addContent(content);
        }

        public static DataContext getDataContext(@NotNull Project project) {
            ToolWindow consoleToolWindows = ToolWindowManager.getInstance(project).getToolWindow(ID);
            ConsolePanel consolePanel = (ConsolePanel) consoleToolWindows.getContentManager().getContent(0).getComponent();
            return DataManager.getInstance().getDataContext(consolePanel);
        }

        @Override
        public boolean shouldBeAvailable(@NotNull Project project) {
            return true;
        }

}
