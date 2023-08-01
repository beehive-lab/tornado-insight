package com.tais.tornado_plugins.util;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class TornadoTWTask {
    private static List<PsiMethod> taskList;
    public static void addTask(Project project, DefaultListModel model){
        //ToolWindow maybe created before the Psi index,
        // so when the Psi index is not finished creating, skip
        if (DumbService.isDumb(project)) return;
        model.clear();
        taskList = new ArrayList<>();
        PsiManagerImpl manager = new PsiManagerImpl(project);
        if (FileEditorManager.getInstance(project).getSelectedFiles().length == 0){
            return;
        }
        PsiFile file = manager.findFile(Objects.requireNonNull(FileEditorManager.getInstance(project).getSelectedFiles()[0]));
        assert file != null;
        taskList = findAnnotatedVariables(file);
        if (taskList == null) return;
        for (PsiMethod task:taskList) {
            String displayName = psiMethodFormat(task);
            model.addElement(displayName);
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

    public static List<PsiMethod> findAnnotatedVariables(PsiFile psiFile) {
        Set<PsiMethod> tornadoTask = new HashSet<>();
        Collection<PsiAnnotation> annotationList = PsiTreeUtil.findChildrenOfType(psiFile, PsiAnnotation.class);
        if (annotationList.isEmpty()) return null;

        for (PsiAnnotation annotation : annotationList) {
            if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                    annotation.getQualifiedName().endsWith("Reduce")) {
                tornadoTask.add(PsiTreeUtil.getParentOfType(annotation, PsiMethod.class));
            }
        }
        return tornadoTask.stream().toList();
    }

    private static String psiMethodFormat(PsiMethod method){
        String methodName = method.getName();
        StringBuilder methodParameters = new StringBuilder();
        if (!method.hasParameters()) return methodName+"(): "+ Objects.requireNonNull(method.getReturnType()).getCanonicalText();
        for (PsiParameter parameter: method.getParameterList().getParameters()) {
            if (methodParameters.isEmpty()) {
                methodParameters.append(Objects.requireNonNull(parameter.getTypeElement()).getText());
            }else {
                methodParameters.append(" ,").append(Objects.requireNonNull(parameter.getTypeElement()).getText());
            }

        }

        return methodName + "(" + methodParameters + "): " + Objects.requireNonNull(method.getReturnType()).getCanonicalText();
    }
}