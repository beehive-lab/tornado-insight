/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
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
    public static void refresh(Project project, VirtualFile virtualFile, DefaultListModel<String> model) {
        if (DumbService.isDumb(project) || model == null) return;

        ReadAction.nonBlocking(() -> {
            DefaultListModel<String> newModel = new DefaultListModel<>();
            List<PsiMethod> newTaskList = new ArrayList<>();
            Map<String, PsiMethod> newTaskMap = new HashMap<>();

            PsiFile file = PsiManager.getInstance(project).findFile(virtualFile);
            if (file == null) return null;

            List<PsiMethod> tasks = findSuitableMethods(file);
            String imports = getImportCode(file);

            if (tasks != null) {
                for (PsiMethod task : tasks) {
                    if (validateTask(task)) {
                        String displayName = psiMethodFormat(task);
                        newTaskList.add(task);
                        newTaskMap.put(displayName, task);
                        newModel.addElement(displayName);
                    }
                }
            }

            // Store global state
            taskList = newTaskList;
            taskMap = newTaskMap;
            psiFile = file;
            importCodeBlock = imports;

            return newModel;

        }).inSmartMode(project)
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState(), newModel -> {
            if (newModel != null) {
                model.clear();
                for (int i = 0; i < newModel.size(); i++) {
                    model.addElement(newModel.get(i));
                }
            }
        }).submit(AppExecutorUtil.getAppExecutorService());
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
                String importText = importStatement.getText();
                if (statementImportsJunit(importText)) {
                    continue;
                }
                importCodeBlock.append(importStatement.getText());
                importCodeBlock.append("\n");
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
        // Kernel entry points registered via .task() must return void
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiTypes.voidType().equals(returnType)) return false;
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

    public static PsiFile getPsiFile() {
        return psiFile;
    }

    private static boolean statementImportsJunit(String importStatement) {
        return importStatement.contains("import org.junit.");
    }

    public static class TaskGraphTransfer {
        public final String direction; // "device" or "host"
        public final String mode; // DataTransferMode.X
        public final List<String> variables;

        public TaskGraphTransfer(String direction, String mode, List<String> variables) {
            this.direction = direction;
            this.mode = mode;
            this.variables = variables;
        }

        @Override
        public String toString() {
            return "transferTo" + capitalize(direction) + "(" + mode + ", " + String.join(", ", variables) + ")";
        }

        private static String capitalize(String s) {
            return s.substring(0, 1).toUpperCase() + s.substring(1);
        }
    }

    /**
     * Extracts all unique {@code transferToDevice(...)} and {@code transferToHost(...)} method calls
     * from a given {@link PsiFile}, specifically those that are part of a {@code TaskGraph} chain.
     * <p>
     * The method identifies all {@link PsiMethodCallExpression}s, filters out non-transfer methods,
     * and ensures that only top-level (non-nested) calls are considered. It deduplicates transfer entries
     * using a {@link Set} of unique keys formed from the transfer direction, mode, and variables.
     * Each valid and unique transfer is stored in a {@link TaskGraphTransfer} object and returned.
     * </p>
     * <p>
     * Nested or repeated calls due to fluent-style chains (e.g., {@code .transferToDevice().task().transferToHost()})
     * are intentionally skipped to avoid duplication.
     * </p>
     *
     * @param psiFile the PSI file representing the source code to analyze
     * @return a list of unique {@link TaskGraphTransfer} objects representing transfer instructions found in the file
     */
    public static List<TaskGraphTransfer> extractTaskGraphTransfers(PsiFile psiFile) {
        Set<String> seen = new HashSet<>();
        List<TaskGraphTransfer> result = new ArrayList<>();

        Collection<PsiMethodCallExpression> methodCalls =
                PsiTreeUtil.findChildrenOfType(psiFile, PsiMethodCallExpression.class);

        for (PsiMethodCallExpression call : methodCalls) {
            PsiMethod method = call.resolveMethod();
            if (method == null) {
                continue;
            }

            String methodName = method.getName();
            if (!isTransferMethod(methodName)) {
                continue;
            }

            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier instanceof PsiMethodCallExpression qualifiedCall) {
                String parentMethod = qualifiedCall.getMethodExpression().getReferenceName();
                if (isTransferMethod(parentMethod)) {
                    continue;
                }
            }
            processTransferCall(call, methodName.equals("transferToDevice") ? "device" : "host", result, seen);
        }

        return result;
    }

    private static boolean isTransferMethod(String name) {
        return "transferToDevice".equals(name) || "transferToHost".equals(name);
    }

    /**
     * Processes a {@code transferToDevice(...)} or {@code transferToHost(...)} method call within a TaskGraph chain
     * and adds a corresponding {@link TaskGraphTransfer} entry to the result list if it hasn't already been seen.
     * <p>
     * The method extracts the {@link DataTransferMode} and all variables passed in the call. It uses a combination
     * of transfer direction, mode, and variable names to create a unique key for deduplication. If this key has
     * not been previously processed, it adds a new {@code TaskGraphTransfer} to the output.
     * </p>
     *
     * @param call     the {@link PsiMethodCallExpression} representing the transfer method call
     * @param direction the direction of transfer, either {@code "device"} or {@code "host"}
     * @param result   the list collecting unique {@link TaskGraphTransfer} entries
     * @param seen     a set of keys used to track and avoid duplicate transfer instructions
     */
    private static void processTransferCall(PsiMethodCallExpression call, String direction,
            List<TaskGraphTransfer> result, Set<String> seen) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 2) return;

        String mode = args[0].getText();
        List<String> vars = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            vars.add(args[i].getText());
        }

        String key = direction + "::" + mode + "::" + String.join(",", vars);
        if (seen.contains(key)) return;
        seen.add(key);

        result.add(new TaskGraphTransfer(direction, mode, vars));
    }

    /**
     * Attempts to extract the original TaskGraph declaration from the given {@link PsiFile}
     * that includes a {@code .task(...)} invocation matching the provided method name.
     * <p>
     * If a matching {@code TaskGraph} declaration is found, the method reference (e.g., {@code SomeClass::someMethod})
     * in the {@code .task(...)} call is replaced with the provided {@code methodWithClass}, ensuring the generated
     * file references the correct target method.
     * </p>
     *
     * @param psiFile         the PSI file to search for TaskGraph declarations
     * @param methodName      the name of the method to match in the .task(...) call (e.g., "vectorAddFloat")
     * @param methodWithClass the replacement method reference (e.g., "GeneratedClassName::vectorAddFloat")
     * @return an {@code Optional<String>} containing the updated TaskGraph declaration if found; otherwise, {@code Optional.empty()}
     */
    public static Optional<String> extractOriginalTaskGraphDeclaration(PsiFile psiFile, String methodName, String methodWithClass) {
        Collection<PsiDeclarationStatement> declarations = PsiTreeUtil.findChildrenOfType(psiFile, PsiDeclarationStatement.class);
        for (PsiDeclarationStatement declaration : declarations) {
            for (PsiElement element : declaration.getDeclaredElements()) {
                if (element instanceof PsiLocalVariable variable) {
                    PsiType type = variable.getType();
                    if (type.getCanonicalText().contains("TaskGraph")) {
                        String code = declaration.getText();
                        if (code.contains(".task(") && code.contains(methodName)) {

                            String updatedCode = code.replaceAll("(\\.task\\s*\\(\\s*\"[^\"]+\"\\s*,\\s*)([\\w\\.]+::\\w+)","$1" + methodWithClass);
                            return Optional.of(updatedCode);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Extracts the TaskGraph variable name from a declaration statement.
     * For example, from "TaskGraph taskGraphA = new TaskGraph("A")..." it extracts "taskGraphA"
     *
     * @param declaration the TaskGraph declaration string
     * @return an Optional containing the variable name if found
     */
    public static Optional<String> extractTaskGraphVariableName(String declaration) {
        // Pattern: TaskGraph <variableName> = ...
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("TaskGraph\\s+(\\w+)\\s*=");
        java.util.regex.Matcher matcher = pattern.matcher(declaration);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    public static Optional<List<TaskParametersInfo>> extractTasksParameters(PsiFile psiFile, String methodName) {
        Collection<PsiDeclarationStatement> declarations =
                PsiTreeUtil.findChildrenOfType(psiFile, PsiDeclarationStatement.class);

        for (PsiDeclarationStatement declaration : declarations) {
            for (PsiElement element : declaration.getDeclaredElements()) {
                if (!(element instanceof PsiLocalVariable variable)) continue;

                PsiType type = variable.getType();
                if (type == null || !type.getCanonicalText().contains("TaskGraph")) continue;

                // Look for .task(...) calls inside this declaration
                Collection<PsiMethodCallExpression> calls =
                        PsiTreeUtil.findChildrenOfType(declaration, PsiMethodCallExpression.class);

                for (PsiMethodCallExpression call : calls) {
                    PsiReferenceExpression methodExpr = call.getMethodExpression();
                    if (!"task".equals(methodExpr.getReferenceName())) continue;

                    PsiExpression[] args = call.getArgumentList().getExpressions();
                    if (args.length < 2) continue; // need at least task name + method ref

                    // 2nd arg must be a method reference to methodName
                    PsiExpression second = args[1];
                    boolean matches;
                    if (second instanceof PsiMethodReferenceExpression mref) {
                        matches = methodName.equals(mref.getReferenceName());
                    } else {
                        String txt = second.getText();
                        matches = txt != null && txt.contains("::" + methodName);
                    }
                    if (!matches) continue;

                    List<TaskParametersInfo> vars = new ArrayList<>();
                    for (int i = 2; i < args.length; i++) {
                        PsiExpression expr = unwrap(args[i]);
                        String name = extractName(expr);
                        String typeText = extractTypeText(expr);
                        if (name == null || name.isEmpty()) name = expr.getText();
                        if (typeText == null || typeText.isEmpty()) typeText = "<unknown>";
                        vars.add(new TaskParametersInfo(name, typeText));
                    }
                    return Optional.of(vars);
                }
            }
        }
        return Optional.empty();
    }

    public static class TaskParametersInfo {
        private final String name;
        private final String type;
        public TaskParametersInfo(String name, String type) { this.name = name; this.type = type; }
        public String getName() { return name; }
        public String getType() { return type; }
        @Override public String toString() { return type + " " + name; }
    }

    // Strip parentheses and casts:  ((float[]) (a))
    private static PsiExpression unwrap(PsiExpression e) {
        PsiExpression cur = e;
        while (true) {
            if (cur instanceof PsiParenthesizedExpression p && p.getExpression() != null) {
                cur = p.getExpression(); continue;
            }
            if (cur instanceof PsiTypeCastExpression c && c.getOperand() != null) {
                cur = c.getOperand(); continue;
            }
            break;
        }
        return cur;
    }

    private static String extractName(PsiExpression e) {
        if (e instanceof PsiArrayAccessExpression arr) {
            return extractName(arr.getArrayExpression());
        }
        if (e instanceof PsiReferenceExpression ref) {
            String simple = ref.getReferenceName();
            return simple != null ? simple : ref.getText();
        }
        return e.getText();
    }

    //   Prefer declared types of fields/locals/params when resolvable,
    //   else fall back to expression.getType().presentableText
    private static String extractTypeText(PsiExpression e) {
        if (e instanceof PsiReferenceExpression ref) {
            PsiElement target = ref.resolve();
            if (target instanceof PsiField f) return f.getType().getPresentableText();
            if (target instanceof PsiLocalVariable lv) return lv.getType().getPresentableText();
            if (target instanceof PsiParameter p) return p.getType().getPresentableText();
        }
        if (e instanceof PsiArrayAccessExpression arr) {
            PsiType t = arr.getType();
            if (t != null) return t.getPresentableText();
        }
        PsiType t = e.getType();
        return t != null ? t.getPresentableText() : null;
    }
}
