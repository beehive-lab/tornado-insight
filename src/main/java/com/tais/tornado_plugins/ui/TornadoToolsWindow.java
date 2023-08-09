package com.tais.tornado_plugins.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.tais.tornado_plugins.listener.ToolWindowOpen;
import com.tais.tornado_plugins.util.TornadoTWTask;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class TornadoToolsWindow implements ToolWindowFactory {
    private static JList list;
    private static DefaultListModel listModel;
    private static DefaultListModel inspectorListModel;
    private static TornadoVM toolsWindow;

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolsWindow = new TornadoVM(toolWindow);
        list = toolsWindow.getTasksList();
        listModel = (DefaultListModel) list.getModel();
        inspectorListModel = (DefaultListModel) toolsWindow.getInspectorList().getModel();
        Content content = ContentFactory.getInstance().
                createContent(toolsWindow.getMainPanel(),"",false);
        toolWindow.getContentManager().addContent(content);
        ToolWindowOpen.RefreshListener.init(project.getMessageBus());
        TornadoTWTask.addTask(project,listModel);
        TornadoTWTask.updateInspectorList(inspectorListModel);
    }

    public static DefaultListModel getListModel(){
        return listModel;
    }

    public static JList getList(){
        return list;
    }

    public static DefaultListModel getInspectorListModel() {
        return inspectorListModel;
    }

    public static TornadoVM getToolsWindow() {
        return toolsWindow;
    }


}
