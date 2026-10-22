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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.inspector.KernelValidator;
import uk.ac.manchester.beehive.tornado.plugins.inspector.TornadoKernels;

import java.util.*;

/**
 * A utility class for managing and working with ToolWindows about the TornadoVM Tasks.
 * <p>
 * This class provides functionality to detect, format, and validate TornadoVM Tasks/methods
 * within the tool window. It holds no state: every helper takes the file or methods it works
 * on, and the tasks of the current file are kept per project by {@link TornadoTaskService}.
 * </p>
 */
public class TornadoTWTask {

    /**
     * Returns the fields declared in {@code psiFile}, keyed by
     * "&lt;modifiers&gt; &lt;type&gt; &lt;name&gt;", with their initializer text (or {@code null}).
     */
    public static Map<String, Object> getFields(PsiFile psiFile) {
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
            if (method != null && !methodCalls.contains(method) && isCollectableHelper(method)) {
                methodCalls.add(method);
                ArrayList<PsiMethod> newMethods = new ArrayList<>();
                newMethods.add(method);
                collectCalledMethods(newMethods, methodCalls);
            }
        }
    }

    /**
     * Determines whether a resolved method should be collected as a kernel helper
     * for code generation. Unlike the previous file-scoped filter, this method
     * accepts helpers defined in any class within the project's source roots.
     *
     * @param method the resolved method to check
     * @return true if the method should be included as a helper in generated code
     */
    private static boolean isCollectableHelper(PsiMethod method) {
        // Must have a body (source available)
        if (method.getBody() == null) return false;

        // Exclude TornadoVM API methods by checking the containing class's package
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            String qualifiedName = containingClass.getQualifiedName();
            if (qualifiedName != null && qualifiedName.startsWith("uk.ac.manchester.tornado.api")) {
                return false;
            }
        }

        // Must be in project sources (not an external library)
        PsiFile file = method.getContainingFile();
        if (file == null) return false;
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null) return false;
        Project project = method.getProject();
        var fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
        // Exclude library sources (e.g., TornadoVM API with attached sources)
        if (fileIndex.isInLibrarySource(vFile) || fileIndex.isInLibraryClasses(vFile)) {
            return false;
        }
        if (!fileIndex.isInSourceContent(vFile)) {
            return false;
        }

        // Exclude main, constructors, TaskGraph builders
        if ("main".equals(method.getName())) return false;
        if (method.isConstructor()) return false;
        if (method.getText().contains("TaskGraph")) return false;

        for (PsiAnnotation annotation : method.getAnnotations()) {
            String qName = annotation.getQualifiedName();
            if ("java.lang.Override".equals(qName)) return false;
        }

        return true;
    }

    /**
     * Returns non-kernel methods defined in the current file, excluding main,
     * constructors, TaskGraph-related, and @Override methods.
     * Retained for backward compatibility with code that needs file-local helpers.
     */
    public static ArrayList<PsiMethod> getOtherMethods(PsiFile psiFile, ArrayList<PsiMethod> methods) {
        Collection<PsiMethod> allMethods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod.class);
        ArrayList<PsiMethod> allMethodsList = new ArrayList<>(allMethods);
        allMethodsList.removeAll(methods);

        allMethodsList.removeIf(method -> "main".equals(method.getName()) ||
                method.isConstructor() || method.getText().contains("TaskGraph")
                || method.getText().contains("@Override"));
        return allMethodsList;
    }
    /**
     * Finds the TornadoVM kernels declared in the given PsiFile, in document order.
     *
     * @param psiFile the file to search in
     * @return the kernels of the file (see {@link TornadoKernels}); empty if none found
     */
    public static List<PsiMethod> findSuitableMethods(PsiFile psiFile) {
        return TornadoKernels.findKernels(psiFile);
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
     * Returns the import statements of the given file, one per line, skipping JUnit imports.
     */
    public static String getImportCode(PsiFile file) {
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

    // Validates a given PsiMethod to ensure it's a valid TornadoVM task
    static boolean validateTask(PsiMethod method) {
        if (method == null) return false;
        PsiElement[] errorElements = PsiTreeUtil.collectElements(method,
                element -> element instanceof PsiErrorElement);
        if (errorElements.length != 0) return false;
        // Kernel entry points registered via .task() must return void
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiTypes.voidType().equals(returnType)) return false;
        // Computed from the inspections' own checks, not from whether they have run
        return !KernelValidator.hasBlockingProblems(method);
    }

    private static boolean statementImportsJunit(String importStatement) {
        return importStatement.contains("import org.junit.");
    }
}
