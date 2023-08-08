package com.tais.tornado_plugins.listener;

import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.wm.ToolWindowManager;
import com.tais.tornado_plugins.entity.ProblemMethods;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;
import com.tais.tornado_plugins.util.TornadoTWTask;
import org.jetbrains.annotations.NotNull;

public class EditorSwitch implements FileEditorManagerListener {

    //When editor selection changed, refresh the window tool
    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        //ProblemMethods.getInstance().clear();
        if (ToolWindowManager.getInstance(event.getManager().
                getProject()).getToolWindow("TornadoVM") == null) return;

        TornadoTWTask.addTask(event.getManager().getProject(), TornadoToolsWindow.getListModel());
    }
}
