package com.tais.tornado_plugins.util;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TornadoTWTask {
    public static void addTask(Project project, DefaultListModel model){
        model.clear();
        List<PsiMethod> taskList = new ArrayList<>();
        PsiManagerImpl manager = new PsiManagerImpl(project);
        if (FileEditorManager.getInstance(project).getSelectedFiles().length == 0){
            return;
        }
        PsiFile file = manager.findFile(Objects.requireNonNull(FileEditorManager.getInstance(project).getSelectedFiles()[0]));
        assert file != null;
        taskList.addAll(PsiTreeUtil.findChildrenOfType(file,PsiMethod.class));
        if (taskList == null){
            return;
        }
        for (PsiMethod task:taskList) {
            model.addElement(task);
        }
        TornadoToolsWindow.getList().repaint();
    }

    public static void updateInspectorList(DefaultListModel model){
        List<LocalInspectionEP> inspections = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList();
        for (LocalInspectionEP inspectionEP : inspections) {
            if (inspectionEP.implementationClass.startsWith("com.tais.tornado_plugins")) {
                LocalInspectionToolWrapper tool = new LocalInspectionToolWrapper(inspectionEP);
                model.addElement(tool.getDisplayName());
                //System.out.println("Custom Inspection: " + tool.getDisplayName());
            }
        }
    }
}
