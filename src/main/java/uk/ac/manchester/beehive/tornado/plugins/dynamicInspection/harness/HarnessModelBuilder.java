/*
 * Copyright (c) 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness;

import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.LocalMemoryParameterAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Field;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.TaskGraphDeclaration;
import uk.ac.manchester.beehive.tornado.plugins.inspector.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link HarnessModel} of a kernel from the PSI. Must run in a read
 * action; the model it returns holds no PSI.
 */
public final class HarnessModelBuilder {

    private HarnessModelBuilder() {
    }

    /**
     * Builds the model of the harness that runs {@code kernel} as class {@code className}.
     */
    @RequiresReadLock
    public static @NotNull HarnessModel build(@NotNull PsiMethod kernel, @NotNull String className) {
        PsiFile psiFile = kernel.getContainingFile();
        List<Field> fields = collectFields(psiFile);

        List<String> helpers = new ArrayList<>();
        ArrayList<PsiMethod> kernels = new ArrayList<>(List.of(kernel));
        for (PsiMethod helper : TornadoTWTask.getCalledMethods(kernels)) {
            helpers.add(helper.getText());
        }

        TaskCall taskCall = findTaskCall(psiFile, kernel);
        TaskGraphDeclaration taskGraph = taskCall != null ? reusableTaskGraph(taskCall, kernel, className) : null;
        List<Parameter> parameters = taskGraph != null
                ? parameters(kernel, argumentNames(taskCall), fields)
                : parameters(kernel, null, fields);

        return new HarnessModel(className, kernel.getName(), TornadoKernels.kind(kernel),
                TornadoTWTask.getImportCode(psiFile).lines().toList(), fields, kernel.getText(), helpers,
                parameters, taskGraph);
    }

    /**
     * A {@code .task(...)} call for the kernel inside a TaskGraph declaration.
     */
    private record TaskCall(PsiDeclarationStatement declaration, PsiLocalVariable variable,
                            PsiMethodCallExpression call) {
        PsiExpression[] kernelArguments() {
            PsiExpression[] args = call.getArgumentList().getExpressions();
            return Arrays.copyOfRange(args, 2, args.length);
        }
    }

    /**
     * Finds the {@code .task("name", Class::kernel, ...)} call for {@code kernel} in a
     * TaskGraph declaration of {@code file}. The class may declare several TaskGraphs
     * that reference this kernel; prefer the one whose argument count matches the
     * kernel's parameter count, so a stale or unrelated TaskGraph is not picked.
     */
    private static @Nullable TaskCall findTaskCall(PsiFile file, PsiMethod kernel) {
        int parameterCount = kernel.getParameterList().getParametersCount();
        TaskCall fallback = null;
        for (PsiDeclarationStatement declaration : PsiTreeUtil.findChildrenOfType(file, PsiDeclarationStatement.class)) {
            for (PsiElement element : declaration.getDeclaredElements()) {
                if (!(element instanceof PsiLocalVariable variable)
                        || !variable.getType().getCanonicalText().contains("TaskGraph")) {
                    continue;
                }
                for (PsiMethodCallExpression call : taskCalls(declaration)) {
                    PsiExpression[] args = call.getArgumentList().getExpressions();
                    if (args.length < 2 || !referencesKernel(args[1], kernel)) {
                        continue;
                    }
                    TaskCall taskCall = new TaskCall(declaration, variable, call);
                    if (args.length - 2 == parameterCount) {
                        return taskCall;
                    }
                    if (fallback == null) {
                        fallback = taskCall;
                    }
                }
            }
        }
        return fallback;
    }

    private static List<PsiMethodCallExpression> taskCalls(PsiElement scope) {
        List<PsiMethodCallExpression> calls = new ArrayList<>();
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(scope, PsiMethodCallExpression.class)) {
            if ("task".equals(call.getMethodExpression().getReferenceName())) {
                calls.add(call);
            }
        }
        return calls;
    }

    /**
     * Whether the method reference passed to {@code .task(...)} is {@code kernel}. A
     * reference that resolves must resolve to the kernel itself; one that does not
     * (e.g. without the TornadoVM API on the classpath) is matched by name.
     */
    private static boolean referencesKernel(PsiExpression expression, PsiMethod kernel) {
        if (expression instanceof PsiMethodReferenceExpression reference) {
            PsiElement target = reference.resolve();
            return target != null ? kernel.getManager().areElementsEquivalent(target, kernel)
                    : kernel.getName().equals(reference.getReferenceName());
        }
        return expression.getText().endsWith("::" + kernel.getName());
    }

    /**
     * Returns the user's TaskGraph declaration with its kernel reference pointing at
     * the harness class, or {@code null} when it cannot be reused: when it has other
     * tasks (their kernels are not in the harness), when it passes anything but
     * distinct variables to the kernel, or when it uses local variables that are not
     * kernel arguments. The harness then builds its own single-task graph.
     */
    private static @Nullable TaskGraphDeclaration reusableTaskGraph(TaskCall taskCall, PsiMethod kernel, String className) {
        PsiDeclarationStatement declaration = taskCall.declaration();
        if (taskCalls(declaration).size() != 1) {
            return null;
        }
        List<String> arguments = argumentNames(taskCall);
        if (arguments == null || arguments.size() != kernel.getParameterList().getParametersCount()
                || new HashSet<>(arguments).size() != arguments.size()) {
            return null;
        }
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(declaration, PsiReferenceExpression.class)) {
            PsiElement target = reference.resolve();
            if ((target instanceof PsiLocalVariable || target instanceof PsiParameter)
                    && target != taskCall.variable() && !arguments.contains(reference.getReferenceName())) {
                return null;
            }
        }

        // Rewrite only the kernel reference of this task
        PsiExpression kernelReference = taskCall.call().getArgumentList().getExpressions()[1];
        int start = kernelReference.getTextRange().getStartOffset() - declaration.getTextRange().getStartOffset();
        String text = declaration.getText();
        String rewritten = text.substring(0, start) + className + "::" + kernel.getName()
                + text.substring(start + kernelReference.getTextLength());

        return new TaskGraphDeclaration(rewritten, taskCall.variable().getName(), graphName(declaration),
                stringValue(taskCall.call().getArgumentList().getExpressions()[0]));
    }

    /**
     * Returns the names of the variables passed to the kernel, or {@code null} if an
     * argument is not a plain variable reference.
     */
    private static @Nullable List<String> argumentNames(TaskCall taskCall) {
        List<String> names = new ArrayList<>();
        for (PsiExpression argument : taskCall.kernelArguments()) {
            PsiExpression expression = PsiUtil.skipParenthesizedExprDown(argument);
            if (!(expression instanceof PsiReferenceExpression reference) || reference.isQualified()
                    || reference.getReferenceName() == null) {
                return null;
            }
            names.add(reference.getReferenceName());
        }
        return names;
    }

    // The name given in 'new TaskGraph("name")'
    private static @Nullable String graphName(PsiDeclarationStatement declaration) {
        for (PsiNewExpression expression : PsiTreeUtil.findChildrenOfType(declaration, PsiNewExpression.class)) {
            PsiJavaCodeReferenceElement classReference = expression.getClassReference();
            PsiExpressionList arguments = expression.getArgumentList();
            if (classReference != null && "TaskGraph".equals(classReference.getReferenceName())
                    && arguments != null && arguments.getExpressionCount() == 1) {
                return stringValue(arguments.getExpressions()[0]);
            }
        }
        return null;
    }

    private static @Nullable String stringValue(PsiExpression expression) {
        return expression instanceof PsiLiteralExpression literal && literal.getValue() instanceof String value
                ? value : null;
    }

    private static List<Field> collectFields(PsiFile psiFile) {
        List<Field> fields = new ArrayList<>();
        for (PsiField field : PsiTreeUtil.findChildrenOfType(psiFile, PsiField.class)) {
            List<String> modifiers = new ArrayList<>();
            for (String modifier : PsiModifier.MODIFIERS) {
                if (field.hasModifierProperty(modifier)) {
                    modifiers.add(modifier);
                }
            }
            PsiExpression initializer = field.getInitializer();
            fields.add(new Field(String.join(" ", modifiers), field.getType().getPresentableText(), field.getName(),
                    initializer != null ? initializer.getText() : null));
        }
        return fields;
    }

    /**
     * The harness variables: one per kernel parameter, with the kernel parameter's
     * type. TornadoVM requires each argument type to match the kernel parameter type
     * exactly. When the user's TaskGraph is reused, the variables take the names of
     * the arguments it passes, so the reused declaration still resolves.
     *
     * @param argumentNames the names of the reused TaskGraph's kernel arguments, or
     *                      {@code null} to use the kernel's parameter names
     */
    private static List<Parameter> parameters(PsiMethod kernel, @Nullable List<String> argumentNames, List<Field> fields) {
        Set<String> localMemoryParams = LocalMemoryParameterAnalyzer.findLocalMemoryParameters(kernel);
        PsiParameter[] kernelParams = kernel.getParameterList().getParameters();
        List<Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < kernelParams.length; i++) {
            String name = argumentNames != null ? argumentNames.get(i) : kernelParams[i].getName();
            String type = kernelParams[i].getType().getCanonicalText(false);
            String intOverride = "int".equals(type) ? findMatchingIntField(fields, name) : null;
            parameters.add(new Parameter(name, type, localMemoryParams.contains(kernelParams[i].getName()), intOverride));
        }
        return parameters;
    }

    /**
     * For an int parameter of the kernel, look in the copied fields for an int
     * field whose name matches the parameter name (case-insensitive) - e.g.
     * parameter 'size' picks up a user-defined 'SIZE' or 'Size' constant.
     * Returns {@code null} when there is none, so the configured size is used.
     */
    static @Nullable String findMatchingIntField(List<Field> fields, String paramName) {
        for (Field field : fields) {
            if ("int".equals(field.type()) && field.name().equalsIgnoreCase(paramName)) {
                return field.name();
            }
        }
        return null;
    }
}
