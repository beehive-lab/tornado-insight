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

import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMember;
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
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.LocalMemoryParameterAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Field;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.TaskGraphDeclaration;
import uk.ac.manchester.beehive.tornado.plugins.inspector.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.inspector.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
        List<PsiMethod> helperMethods = helpers(kernel);
        List<String> helpers = new ArrayList<>();
        for (PsiMethod helper : helperMethods) {
            helpers.add(helper.getText());
        }
        List<PsiField> psiFields = fields(kernel, helperMethods);
        List<Field> fields = new ArrayList<>();
        for (PsiField field : psiFields) {
            fields.add(toField(field));
        }

        // The kernel file's imports, then those of the files helpers and fields come from
        Set<String> imports = new LinkedHashSet<>(TornadoTWTask.getImportCode(psiFile).lines().toList());
        for (PsiMember member : ContainerUtil.concat(helperMethods, psiFields)) {
            PsiFile file = member.getContainingFile();
            if (file != null && file != psiFile) {
                imports.addAll(TornadoTWTask.getImportCode(file).lines().toList());
            }
        }

        TaskCall taskCall = findTaskCall(psiFile, kernel);
        TaskGraphDeclaration taskGraph = taskCall != null ? reusableTaskGraph(taskCall, kernel, className) : null;
        List<Parameter> parameters = taskGraph != null
                ? parameters(kernel, argumentNames(taskCall), fields)
                : parameters(kernel, null, fields);

        return new HarnessModel(className, kernel.getName(), TornadoKernels.kind(kernel),
                new ArrayList<>(imports), fields, kernel.getText(), helpers,
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

    /**
     * The helper methods copied with the kernel: the methods the kernel inspections
     * analyse as inlined into it (see {@link KernelCallGraphAnalyzer}), so code
     * generation and the inspections agree on what the kernel runs. {@code @Override}
     * methods are left out, as the annotation does not compile in the harness.
     */
    private static List<PsiMethod> helpers(PsiMethod kernel) {
        List<PsiMethod> helpers = new ArrayList<>();
        for (PsiMethod method : KernelCallGraphAnalyzer.resolve(kernel).getAnalyzableMethods()) {
            if (method != kernel && !method.hasAnnotation(CommonClassNames.JAVA_LANG_OVERRIDE)) {
                helpers.add(method);
            }
        }
        return helpers;
    }

    /**
     * The fields copied into the harness: those of the kernel's class, and those of
     * other project classes that the kernel or a helper uses, together with the
     * fields their initializers use. A field comes after the copied fields its
     * initializer refers to, and otherwise in declaration order, so the copy has no
     * illegal forward reference. Of two fields with the same name the first is kept.
     */
    private static List<PsiField> fields(PsiMethod kernel, List<PsiMethod> helpers) {
        Set<PsiField> candidates = new LinkedHashSet<>();
        PsiClass kernelClass = kernel.getContainingClass();
        if (kernelClass != null) {
            for (PsiField field : kernelClass.getFields()) {
                if (!(field instanceof PsiEnumConstant)) {
                    candidates.add(field);
                }
            }
        }
        for (PsiMethod method : ContainerUtil.prepend(helpers, kernel)) {
            candidates.addAll(referencedFields(method));
        }

        Set<PsiField> ordered = new LinkedHashSet<>();
        for (PsiField field : candidates) {
            addWithDependencies(field, ordered, new HashSet<>());
        }
        List<PsiField> fields = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (PsiField field : ordered) {
            if (names.add(field.getName())) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static void addWithDependencies(PsiField field, Set<PsiField> ordered, Set<PsiField> visiting) {
        if (ordered.contains(field) || !visiting.add(field)) {
            return;
        }
        PsiExpression initializer = field.getInitializer();
        if (initializer != null) {
            for (PsiField dependency : referencedFields(initializer)) {
                addWithDependencies(dependency, ordered, visiting);
            }
        }
        ordered.add(field);
    }

    // Fields of project classes referenced in 'scope', in order of appearance
    private static Set<PsiField> referencedFields(PsiElement scope) {
        Set<PsiField> fields = new LinkedHashSet<>();
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(scope, PsiReferenceExpression.class)) {
            if (reference.resolve() instanceof PsiField field && !(field instanceof PsiEnumConstant) && isProjectField(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean isProjectField(PsiField field) {
        PsiFile file = field.getContainingFile();
        VirtualFile virtualFile = file != null ? file.getVirtualFile() : null;
        return virtualFile != null
                && ProjectRootManager.getInstance(field.getProject()).getFileIndex().isInSourceContent(virtualFile);
    }

    private static Field toField(PsiField field) {
        List<String> modifiers = new ArrayList<>();
        for (String modifier : PsiModifier.MODIFIERS) {
            if (field.hasModifierProperty(modifier)) {
                modifiers.add(modifier);
            }
        }
        PsiExpression initializer = field.getInitializer();
        return new Field(String.join(" ", modifiers), field.getType().getCanonicalText(false), field.getName(),
                initializer != null ? initializer.getText() : null);
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
     * For an int parameter of the kernel, look in the copied fields for a static int
     * field whose name matches the parameter name (case-insensitive) - e.g.
     * parameter 'size' picks up a user-defined 'SIZE' or 'Size' constant.
     * Returns {@code null} when there is none, so the configured size is used.
     */
    static @Nullable String findMatchingIntField(List<Field> fields, String paramName) {
        for (Field field : fields) {
            // main is static, so only a static field can initialise its variables
            if ("int".equals(field.type()) && field.isStatic() && field.name().equalsIgnoreCase(paramName)) {
                return field.name();
            }
        }
        return null;
    }
}
