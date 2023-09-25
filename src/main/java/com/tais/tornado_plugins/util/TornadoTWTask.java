package com.tais.tornado_plugins.util;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.impl.PsiManagerImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.components.JBLabel;
import com.tais.tornado_plugins.entity.ProblemMethods;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A utility class for managing and working with ToolWindows about the TornadoVM Tasks.
 * <p>
 * This class provides functionality to detect, format, and validate TornadoVM Tasks/methods
 * within the tool window.
 * </p>
 */
public class TornadoTWTask {

    // Lists and Maps for storing tasks and their details
    private static List<PsiMethod> taskList;
    private static String importCodeBlock;
    private static Map<String, PsiMethod> taskMap;

    /**
     * Adds TornadoVM Tasks to the given UI data model.
     *
     * @param project the IntelliJ project
     * @param model the list model to which tasks should be added
     */
    public synchronized static void addTask(Project project, DefaultListModel<String> model) {
        //ToolWindow maybe created before the Psi index,
        // so when the Psi index is not finished creating, skip
        if (DumbService.isDumb(project) || model == null) return;
        model.clear();
        taskList = new ArrayList<>();
        taskMap = new HashMap<>();

        PsiManagerImpl manager = new PsiManagerImpl(project);
        if (FileEditorManager.getInstance(project).getSelectedFiles().length == 0) return;

        PsiFile file = manager.findFile(Objects.requireNonNull(FileEditorManager.getInstance(project).getSelectedFiles()[0]));
        assert file != null;
        taskList = findAnnotatedVariables(file);
        if (taskList == null) return;
        for (PsiMethod task : taskList) {
            if (validateTask(task)) {
                String displayName = psiMethodFormat(task);
                taskMap.put(displayName, task);
                model.addElement(displayName);
            }
        }
        TornadoToolsWindow.getList().repaint();
    }

    /**
     * Updates the inspection list with custom inspections related to the TornadoVM.
     *
     * @param model the list model for UI to which inspections should be added
     */
    public static void updateInspectorList(DefaultListModel model) {
        List<LocalInspectionEP> inspections = LocalInspectionEP.LOCAL_INSPECTION.getExtensionList();
        for (LocalInspectionEP inspectionEP : inspections) {
            if (inspectionEP.implementationClass.startsWith("com.tais.tornado_plugins")) {
                LocalInspectionToolWrapper tool = new LocalInspectionToolWrapper(inspectionEP);
                model.addElement(tool.getDisplayName());
            }
        }
    }

    public static void renderInspectorList(JList list){
        list.add(new JBLabel("Data type inspector"));
        list.add(new JBLabel("This is the demo description of the datatype insoector"));
    }

    /**
     * Finds methods in the given PsiFile that are annotated with TornadoVM related annotations.
     *
     * @param psiFile the file to search in
     * @return a list of methods that have Tornado VM related annotations, or null if none found
     */
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

    /**
     * Formats the PsiMethod details into a readable string format.
     *
     * @param method the PsiMethod to format
     * @return a formatted string representation of the method
     */
    public static String psiMethodFormat(PsiMethod method) {
        String methodName = method.getName();
        StringBuilder methodParameters = new StringBuilder();
        if (!method.hasParameters())
            return methodName + "(): " + Objects.requireNonNull(method.getReturnType()).getCanonicalText();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (methodParameters.isEmpty()) {
                methodParameters.append(Objects.requireNonNull(parameter.getTypeElement()).getText());
            } else {
                methodParameters.append(", ").append(Objects.requireNonNull(parameter.getTypeElement()).getText());
            }
        }
        return methodName + "(" + methodParameters + "): " + Objects.requireNonNull(method.getReturnType()).getCanonicalText();
    }

    // Internal utility method to validate a given PsiMethod to ensure it's a valid TornadoVM task
    private static boolean validateTask(PsiMethod method) {
        PsiElement[] errorElements = PsiTreeUtil.collectElements(method,
                element -> element instanceof PsiErrorElement);
        if (errorElements.length != 0) return false;
        return !ProblemMethods.getInstance().getMethodSet().contains(method.getText());
    }
}
