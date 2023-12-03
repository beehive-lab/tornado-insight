package com.tais.tornado_plugins.service;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.tais.tornado_plugins.dynamicInspection.DynamicInspection;
import com.tais.tornado_plugins.ui.settings.TornadoSettingState;
import com.tais.tornado_plugins.ui.toolwindow.EmptySelectionWarningDialog;
import com.tais.tornado_plugins.util.DataKeys;
import com.tais.tornado_plugins.util.TornadoTWTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class RunInspectionAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        List<String> data = e.getData(DataKeys.TORNADO_SELECTED_LIST);
        assert data != null;
        if (data.isEmpty()) {
            new EmptySelectionWarningDialog().show();
        }else {
            ArrayList<PsiMethod> methods = TornadoTWTask.getMethods(data);
            DynamicInspection.process(e.getProject(), methods);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        e.getPresentation().setEnabled(project != null && TornadoSettingState.getInstance().isValid);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
