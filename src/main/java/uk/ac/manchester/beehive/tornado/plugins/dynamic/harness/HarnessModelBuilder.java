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

package uk.ac.manchester.beehive.tornado.plugins.dynamic.harness;

import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.Field;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.TaskGraphDeclaration;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the {@link HarnessModel} of a kernel from the PSI. Must run in a read
 * action; the model it returns holds no PSI.
 */
public final class HarnessModelBuilder {


    private HarnessModelBuilder() {
    }

    /**
     * The project code a harness copies with a kernel, and so runs.
     *
     * @param helpers the methods the kernel calls, directly or not
     * @param fields  the fields copied with their initializers
     */
    public record Copied(@NotNull List<PsiMethod> helpers, @NotNull List<PsiField> fields) {
    }

    /**
     * Builds the model of the harness that runs {@code kernel} as class {@code className}, with
     * every field of the kernel's class (see {@link #build(PsiMethod, String, boolean)}).
     */
    @RequiresReadLock
    public static @NotNull HarnessModel build(@NotNull PsiMethod kernel, @NotNull String className) {
        return build(kernel, className, true);
    }

    /**
     * Builds the model of the harness that runs {@code kernel} as class {@code className}.
     *
     * @param classFields whether every field of the kernel's class is copied, so that an
     *                    {@code int} parameter can take the value of a static field of its name,
     *                    or only the fields that the copied code uses
     */
    @RequiresReadLock
    public static @NotNull HarnessModel build(@NotNull PsiMethod kernel, @NotNull String className, boolean classFields) {
        PsiFile psiFile = kernel.getContainingFile();
        Copied copied = copied(kernel, classFields);
        List<PsiMethod> helperMethods = copied.helpers();
        List<PsiField> psiFields = copied.fields();
        Map<PsiMember, String> names = harnessNames(kernel, helperMethods, psiFields);
        List<String> helpers = new ArrayList<>();
        for (PsiMethod helper : helperMethods) {
            helpers.add(harnessText(helper, names, className));
        }
        List<Field> fields = new ArrayList<>();
        for (PsiField field : psiFields) {
            fields.add(toField(field, names, className));
        }

        // The kernel file's imports, then those of the files helpers and fields come from
        Set<String> imports = new LinkedHashSet<>(imports(psiFile));
        for (PsiMember member : ContainerUtil.concat(helperMethods, psiFields)) {
            PsiFile file = member.getContainingFile();
            if (file != null && file != psiFile) {
                imports.addAll(imports(file));
            }
        }

        TaskCall taskCall = findTaskCall(psiFile, kernel);
        TaskGraphDeclaration taskGraph = taskCall != null ? reusableTaskGraph(taskCall, kernel, className) : null;
        List<Parameter> parameters = taskGraph != null
                ? parameters(kernel, argumentNames(taskCall), fields)
                : parameters(kernel, null, fields);

        return new HarnessModel(className, kernel.getName(), TornadoKernels.kind(kernel),
                new ArrayList<>(imports), fields, harnessText(kernel, names, className), helpers,
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
     * kernel arguments, or, for a KernelContext or TileContext kernel, when the graph
     * or task name is not a string literal (the grid scheduler needs both). The
     * harness then builds its own single-task graph.
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

        // Project classes are not on the harness classpath, so a declaration naming one
        // (e.g. 'new TaskGraph(Kernels.GRAPH)') would not compile; the harness then
        // builds its own TaskGraph. The kernel reference is rewritten below.
        PsiExpression kernelReference = taskCall.call().getArgumentList().getExpressions()[1];
        for (PsiJavaCodeReferenceElement reference : PsiTreeUtil.findChildrenOfType(declaration, PsiJavaCodeReferenceElement.class)) {
            if (!PsiTreeUtil.isAncestor(kernelReference, reference, false)
                    && reference.resolve() instanceof PsiClass psiClass && isProjectClass(psiClass)) {
                return null;
            }
        }

        String graphName = graphName(declaration);
        String taskName = stringValue(taskCall.call().getArgumentList().getExpressions()[0]);
        TornadoKernels.Kind kind = TornadoKernels.kind(kernel);
        if ((kind == TornadoKernels.Kind.KERNEL_CONTEXT || kind == TornadoKernels.Kind.TILE_CONTEXT)
                && (graphName == null || taskName == null)) {
            // The grid scheduler is keyed by "<graph>.<task>"
            return null;
        }

        // Rewrite only the kernel reference of this task
        int start = kernelReference.getTextRange().getStartOffset() - declaration.getTextRange().getStartOffset();
        String text = declaration.getText();
        String rewritten = text.substring(0, start) + className + "::" + kernel.getName()
                + text.substring(start + kernelReference.getTextLength());

        return new TaskGraphDeclaration(rewritten, taskCall.variable().getName(), graphName, taskName);
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
     * The helpers and fields that {@link #build(PsiMethod, String, boolean)} copies with
     * {@code kernel}.
     */
    @RequiresReadLock
    public static @NotNull Copied copied(@NotNull PsiMethod kernel, boolean classFields) {
        List<PsiMethod> helpers = helpers(kernel);
        return new Copied(helpers, fields(kernel, helpers, classFields));
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
     * The fields copied into the harness: those of the kernel's class (if
     * {@code classFields}), and those of project classes that the kernel or a helper
     * uses, together with the fields their initializers use. A field comes after the copied fields its
     * initializer refers to, and otherwise in declaration order, so the copy has no
     * illegal forward reference. Fields with the same name are renamed by
     * {@link #harnessNames}.
     */
    private static List<PsiField> fields(PsiMethod kernel, List<PsiMethod> helpers, boolean classFields) {
        Set<PsiField> candidates = new LinkedHashSet<>();
        PsiClass kernelClass = kernel.getContainingClass();
        if (classFields && kernelClass != null) {
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
        return new ArrayList<>(ordered);
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
            if (reference.resolve() instanceof PsiField field && !(field instanceof PsiEnumConstant) && isProjectSource(field)) {
                fields.add(field);
            }
        }
        return fields;
    }

    // Whether 'element' is in the project's sources (a class, member or package directory)
    private static boolean isProjectSource(PsiElement element) {
        VirtualFile virtualFile = PsiUtilCore.getVirtualFile(element);
        return virtualFile != null
                && ProjectRootManager.getInstance(element.getProject()).getFileIndex().isInSourceContent(virtualFile);
    }

    // A class of the project's own code; the TornadoVM API is on the harness classpath
    // even when it is in the sources (e.g. when working on TornadoVM itself)
    private static boolean isProjectClass(PsiClass psiClass) {
        String name = psiClass.getQualifiedName();
        return isProjectSource(psiClass) && (name == null || !name.startsWith(TornadoKernels.TORNADO_API));
    }

    /**
     * The import statements of {@code file} that the harness compiles with: all but
     * JUnit's and those of project classes, which are not on the harness classpath
     * (the members the harness uses are copied into it).
     */
    private static List<String> imports(PsiFile file) {
        List<String> imports = new ArrayList<>();
        if (!(file instanceof PsiJavaFile javaFile) || javaFile.getImportList() == null) {
            return imports;
        }
        for (PsiImportStatement statement : javaFile.getImportList().getImportStatements()) {
            String text = statement.getText();
            if (!text.contains("import org.junit.") && !importsProjectSource(statement)) {
                imports.add(text);
            }
        }
        return imports;
    }

    private static boolean importsProjectSource(PsiImportStatement statement) {
        PsiElement target = statement.resolve();
        if (target instanceof PsiPackage psiPackage) {
            PsiDirectory[] directories = psiPackage.getDirectories();
            return !(psiPackage.getQualifiedName() + ".").startsWith(TornadoKernels.TORNADO_API) && directories.length > 0
                    && Arrays.stream(directories).allMatch(HarnessModelBuilder::isProjectSource);
        }
        return target instanceof PsiClass psiClass && isProjectClass(psiClass);
    }

    /**
     * The names of the copied methods and fields in the harness. A member keeps its
     * name unless an earlier one took it (a field with the same name, a method with
     * the same name and parameter types); it is then prefixed with its class, e.g.
     * {@code MathUtil_clamp}. The kernel's class goes first, so the kernel and its
     * class's members keep their names.
     */
    private static Map<PsiMember, String> harnessNames(PsiMethod kernel, List<PsiMethod> helpers, List<PsiField> fields) {
        PsiClass kernelClass = kernel.getContainingClass();
        Comparator<PsiMember> kernelClassFirst = Comparator.comparing(m -> m.getContainingClass() != kernelClass);
        Map<PsiMember, String> names = new HashMap<>();

        // main(String[]) is the harness's own
        Set<String> methodSignatures = new HashSet<>(Set.of("main(java.lang.String[])"));
        List<PsiMethod> methods = new ArrayList<>(ContainerUtil.prepend(helpers, kernel));
        methods.sort(kernelClassFirst);
        for (PsiMethod method : methods) {
            StringBuilder parameterTypes = new StringBuilder("(");
            for (PsiParameter parameter : method.getParameterList().getParameters()) {
                if (parameterTypes.length() > 1) {
                    parameterTypes.append(",");
                }
                parameterTypes.append(TypeConversionUtil.erasure(parameter.getType()).getCanonicalText());
            }
            names.put(method, harnessName(method, parameterTypes.append(")").toString(), methodSignatures));
        }

        Set<String> fieldNames = new HashSet<>();
        List<PsiField> sortedFields = new ArrayList<>(fields);
        sortedFields.sort(kernelClassFirst);
        for (PsiField field : sortedFields) {
            names.put(field, harnessName(field, "", fieldNames));
        }
        return names;
    }

    // The first of name, Class_name, Class_name2, ... whose key (name + suffix) is not taken
    private static String harnessName(PsiMember member, String suffix, Set<String> taken) {
        String name = member.getName();
        if (taken.add(name + suffix)) {
            return name;
        }
        PsiClass owner = member.getContainingClass();
        String base = (owner != null && owner.getName() != null ? owner.getName() + "_" : "") + name;
        String candidate = base;
        for (int i = 2; !taken.add(candidate + suffix); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    /**
     * Returns the source text of {@code element} as the harness copies it: references
     * to copied members take the members' harness names, and lose a class qualifier
     * ({@code MathUtil.clamp(x)} becomes {@code clamp(x)}, {@code Kernels.SCALE}
     * becomes {@code SCALE}), since the copies all live in the harness class. A
     * method reference keeps its form, with the harness class as qualifier.
     */
    private static String harnessText(PsiElement element, Map<PsiMember, String> names, String className) {
        int base = element.getTextRange().getStartOffset();
        List<TextEdit> edits = new ArrayList<>();
        for (PsiReferenceExpression reference : PsiTreeUtil.collectElementsOfType(element, PsiReferenceExpression.class)) {
            PsiElement nameElement = reference.getReferenceNameElement();
            if (nameElement == null || !(reference.resolve() instanceof PsiMember target) || !names.containsKey(target)) {
                continue;
            }
            String name = names.get(target);
            PsiElement qualifier = reference.getQualifier();
            boolean classQualified = qualifier instanceof PsiJavaCodeReferenceElement q && q.resolve() instanceof PsiClass;
            int nameStart = nameElement.getTextRange().getStartOffset() - base;
            int nameEnd = nameElement.getTextRange().getEndOffset() - base;
            if (reference instanceof PsiMethodReferenceExpression) {
                if (classQualified) {
                    edits.add(new TextEdit(qualifier.getTextRange().getStartOffset() - base,
                            qualifier.getTextRange().getEndOffset() - base, className));
                }
                if (!name.equals(target.getName())) {
                    edits.add(new TextEdit(nameStart, nameEnd, name));
                }
            } else if (classQualified) {
                edits.add(new TextEdit(reference.getTextRange().getStartOffset() - base, nameEnd, name));
            } else if (!name.equals(target.getName())) {
                edits.add(new TextEdit(nameStart, nameEnd, name));
            }
        }

        if (element instanceof PsiMethod method && method.getNameIdentifier() != null
                && !method.getName().equals(names.get(method))) {
            edits.add(new TextEdit(method.getNameIdentifier().getTextRange().getStartOffset() - base,
                    method.getNameIdentifier().getTextRange().getEndOffset() - base, names.get(method)));
        }

        StringBuilder text = new StringBuilder(element.getText());
        int limit = text.length();
        edits.sort(Comparator.comparingInt(TextEdit::start).reversed());
        for (TextEdit edit : edits) {
            // Edits never overlap (a class qualifier holds no member reference); skip one that would
            if (edit.end() <= limit) {
                text.replace(edit.start(), edit.end(), edit.text());
                limit = edit.start();
            }
        }
        return text.toString();
    }

    // Replaces [start, end) of an element's text
    private record TextEdit(int start, int end, String text) {
    }

    private static Field toField(PsiField field, Map<PsiMember, String> names, String className) {
        List<String> modifiers = new ArrayList<>();
        for (String modifier : PsiModifier.MODIFIERS) {
            if (field.hasModifierProperty(modifier)) {
                modifiers.add(modifier);
            }
        }
        PsiExpression initializer = field.getInitializer();
        return new Field(String.join(" ", modifiers), field.getType().getCanonicalText(false), names.get(field),
                initializer != null ? harnessText(initializer, names, className) : null);
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
