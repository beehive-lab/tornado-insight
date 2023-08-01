package com.tais.tornado_plugins.ui;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.tais.tornado_plugins.message.RefreshListener;
import com.tais.tornado_plugins.util.TornadoTWTask;
import org.jetbrains.annotations.NotNull;
import com.tais.tornado_plugins.ui.TornadoVM;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
