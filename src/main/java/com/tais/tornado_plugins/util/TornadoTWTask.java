package com.tais.tornado_plugins.util;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.entity.ProblemMethods;

import javax.swing.*;
import java.util.*;

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
    public static void refresh(Project project, VirtualFile virtualFile, DefaultListModel<String> model){
        if (DumbService.isDumb(project) || model == null) return;
        model.clear();
        taskList = new ArrayList<>();
        taskMap = new HashMap<>();
        PsiFile file = PsiManager.getInstance(project).findFile(virtualFile);
        assert file != null;
        importCodeBlock = getImportCode(file);
        taskList = findAnnotatedVariables(file);
        if (taskList == null) return;
        for (PsiMethod task : taskList) {
            if (validateTask(task)) {
                String displayName = psiMethodFormat(task);
                taskMap.put(displayName, task);
                model.addElement(displayName);
            }
        }
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

    /**
     * Retrieves a list of PsiMethod objects based on a list of method names.
     *
     * @param methodsList the list of method names
     * @return a list of corresponding PsiMethod objects, or null if none found
     */
    public static ArrayList<PsiMethod> getMethods(List<String> methodsList) {
        if (methodsList == null || methodsList.isEmpty()) return null;
        ArrayList<PsiMethod> psiMethodsList = new ArrayList<>();
        for (Object method : methodsList) {
            if (taskMap.containsKey(method.toString())) {
                psiMethodsList.add(taskMap.get(method.toString()));
            }
        }
        return psiMethodsList;
    }

    // Internal utility method to retrieve import code from the given PsiFile
    private static String getImportCode(PsiFile file) {
        StringBuilder importCodeBlock = new StringBuilder();
        if (!(file instanceof PsiJavaFile javaFile)) {
            return importCodeBlock.toString();
        }
        PsiImportList importList = javaFile.getImportList();

        if (importList != null) {
            PsiImportStatement[] importStatements = importList.getImportStatements();
            for (PsiImportStatement importStatement : importStatements) {
                importCodeBlock.append(importStatement.getText()).append("\n");
            }
        }
        return importCodeBlock.toString();
    }

    // Internal utility method to validate a given PsiMethod to ensure it's a valid TornadoVM task
    private static boolean validateTask(PsiMethod method) {
        if (method == null) return false;
        PsiElement[] errorElements = PsiTreeUtil.collectElements(method,
                element -> element instanceof PsiErrorElement);
        if (errorElements.length != 0) return false;
        return !ProblemMethods.getInstance().getMethodSet().contains(method.getText());
    }

    /**
     * Returns the stored import code block.
     *
     * @return a string representing the import code block
     */
    public static String getImportCodeBlock() {
        return importCodeBlock;
    }
}
