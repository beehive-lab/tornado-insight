/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
 *  The University of Manchester.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.entity.ProblemMethods;

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
    private static PsiFile psiFile;

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
        psiFile = file;
        assert file != null;
        importCodeBlock = getImportCode(file);
        taskList = findSuitableMethods(file);
        if (taskList == null) return;
        for (PsiMethod task : taskList) {
            if (validateTask(task)) {
                String displayName = psiMethodFormat(task);
                taskMap.put(displayName, task);
                model.addElement(displayName);
            }
        }
    }

    public static Map<String, Object> getFields() {
        Map<String, Object> methodsAndFields = new HashMap<>();

        Collection<PsiField> allFields = PsiTreeUtil.findChildrenOfType(psiFile, PsiField.class);
        ArrayList<PsiField> allFieldsList = new ArrayList<>(allFields);

        for (PsiField field : allFieldsList) {
            PsiExpression initializer = field.getInitializer();
            Object value = null;
            if (initializer != null) {
                value = initializer.getText();
            }
            methodsAndFields.put(getTypeAndModifiers(field)+field.getName(), value);
        }
        return methodsAndFields;
    }

    private static String getTypeAndModifiers(PsiField field) {
        StringBuilder modifiers = new StringBuilder();
        for (String modifier : PsiModifier.MODIFIERS) {
            if (field.hasModifierProperty(modifier)) {
                modifiers.append(modifier);
                modifiers.append(" ");
            }
        }
        modifiers.append(field.getType().getPresentableText());
        modifiers.append(" ");
        return modifiers.toString();
    }

    public static ArrayList<PsiMethod> getCalledMethods(ArrayList<PsiMethod> methods) {
        Set<PsiMethod> methodCalls = new HashSet<>();
        collectCalledMethods(methods, methodCalls);
        return new ArrayList<>(methodCalls);
    }

    private static void collectCalledMethods(ArrayList<PsiMethod> methods, Set<PsiMethod> methodCalls) {
        ArrayList<PsiMethodCallExpression> allMethodCalls = new ArrayList<>();

        for (PsiMethod psiMethod : methods) {
            Collection<PsiMethodCallExpression> methodCallExpressions = PsiTreeUtil.findChildrenOfType(psiMethod, PsiMethodCallExpression.class);
            allMethodCalls.addAll(methodCallExpressions);
        }

        for (PsiMethodCallExpression call : allMethodCalls) {
            PsiMethod method = call.resolveMethod();
            if (method != null && !methodCalls.contains(method)) {
                PsiClass containingClass = method.getContainingClass();
                if (containingClass != null && getOtherMethods(methods).contains(method)) {
                    methodCalls.add(method);
                    ArrayList<PsiMethod> newMethods = new ArrayList<>();
                    newMethods.add(method);
                    collectCalledMethods(newMethods, methodCalls);
                }
            }
        }
    }


    public static ArrayList<PsiMethod> getOtherMethods(ArrayList<PsiMethod> methods) {
        Collection<PsiMethod> allMethods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class);
        ArrayList<PsiMethod> allMethodsList = new ArrayList<>(allMethods);
        allMethodsList.removeAll(methods);

        allMethodsList.removeIf(method -> "main".equals(method.getName()) ||
                method.isConstructor() || method.getText().contains("TaskGraph")
                || method.getText().contains("@Override"));
        return allMethodsList;
    }
    /**
     * Finds methods in the given PsiFile that are annotated with TornadoVM related annotations.
     *
     * @param psiFile the file to search in
     * @return a list of methods that have Tornado VM related annotations, or null if none found
     */
    public static List<PsiMethod> findSuitableMethods(PsiFile psiFile) {
        Set<PsiMethod> tornadoTask = new HashSet<>();
        Collection<PsiAnnotation> annotationList = PsiTreeUtil.findChildrenOfType(psiFile, PsiAnnotation.class);
        for (PsiAnnotation annotation : annotationList) {
            if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                    annotation.getQualifiedName().endsWith("Reduce")) {
                tornadoTask.add(PsiTreeUtil.getParentOfType(annotation, PsiMethod.class));
            }
        }
        Collection<PsiMethod> methodList = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class);
        for (PsiMethod method: methodList) {
            if (!tornadoTask.contains(method)) {
                PsiParameter[] parameterList = method.getParameterList().getParameters();
                for (PsiParameter parameter: parameterList) {
                    if (parameter.getType().getPresentableText().equals("KernelContext")) {
                        tornadoTask.add(method);
                    }
                }
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
                if (!importStatement.getText().equals("import org.junit.Test;")) {
                    importCodeBlock.append(importStatement.getText()).append("\n");
                }
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
