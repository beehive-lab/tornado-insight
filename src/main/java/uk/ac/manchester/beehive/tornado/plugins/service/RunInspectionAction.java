package uk.ac.manchester.beehive.tornado.plugins.service;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.DynamicInspection;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow.EmptySelectionWarningDialog;
import uk.ac.manchester.beehive.tornado.plugins.util.DataKeys;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;
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
