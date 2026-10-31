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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassObjectAccessExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModelBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a candidate kernel may use, since the verification harness compiles and runs the model's
 * code on this machine: the part of the TornadoVM API that runs on the device, {@code Math}, the
 * primitive wrappers and the project's own code. Anything else (the JDK's I/O, processes,
 * reflection, class loading, TornadoVM's host API, ...) is refused before the kernel is run,
 * whatever the kernel inspections say. The project methods and fields the harness copies with the
 * kernel (see {@link HarnessModelBuilder#copied}) run too, so they are held to the same rules.
 * The checks are on what names resolve to, so a qualified or imported name cannot get around them.
 */
final class RunnableKernel {

    private static final Set<String> JAVA_LANG = Set.of("java.lang.Math", "java.lang.StrictMath",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Character",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean");
    private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");
    // What a kernel runs on the device. The rest of the API (task graphs, execution plans, the
    // runtime and its settings, ...) runs on the host, and would run if TornadoVM fell back to
    // running the kernel as Java
    private static final List<String> DEVICE_PACKAGES = List.of(KernelReplacement.TORNADO_API + "types.",
            KernelReplacement.TORNADO_API + "math.", KernelReplacement.TORNADO_API + "annotations.",
            KernelReplacement.TORNADO_API + "tile.");
    private static final String KERNEL_CONTEXT = KernelReplacement.TORNADO_API + "KernelContext";
    // javac reads these before parsing, the PSI does not: code could hide in a comment
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u+[0-9a-fA-F]{4}");

    private RunnableKernel() {
    }

    /**
     * The problems that keep {@code kernel} from being run, as messages for the model; empty if
     * it may run. Must be called with read access, on a kernel whose names resolve.
     */
    static @NotNull List<String> problems(@NotNull PsiMethod kernel) {
        Set<String> problems = new LinkedHashSet<>();
        PsiFile file = kernel.getContainingFile();
        check(kernel, kernel.getBody(), "the kernel", file, problems);
        HarnessModelBuilder.Copied copied = HarnessModelBuilder.copied(kernel, false);
        for (PsiMethod helper : copied.helpers()) {
            check(helper, helper.getBody(), "`" + helper.getName() + "`, which the kernel calls,", file, problems);
        }
        for (PsiField field : copied.fields()) {
            check(field, field.getInitializer(), "the value of `" + field.getName() + "`, which the kernel uses,", file, problems);
        }
        return List.copyOf(problems);
    }

    /**
     * The project code that {@code kernel}, its helpers and its fields use but the harness does not
     * copy, as the text that uses it: classes, enum constants, constructors and instance members.
     * The harness could not compile the kernel, which is not the kernel's fault. Empty if there is
     * none. Must be called with read access, on a kernel that {@link #problems} accepts.
     */
    static @NotNull List<String> notCopied(@NotNull PsiMethod kernel) {
        PsiFile file = kernel.getContainingFile();
        HarnessModelBuilder.Copied copied = HarnessModelBuilder.copied(kernel, false);
        Set<PsiMember> members = new HashSet<>(copied.helpers());
        members.addAll(copied.fields());
        members.add(kernel);
        List<PsiElement> code = new ArrayList<>();
        code.add(kernel);
        code.addAll(copied.helpers());
        code.addAll(copied.fields());
        Set<String> used = new LinkedHashSet<>();
        for (PsiElement scope : code) {
            for (PsiJavaCodeReferenceElement reference : PsiTreeUtil.findChildrenOfType(scope, PsiJavaCodeReferenceElement.class)) {
                PsiElement target = reference.resolve();
                if (!(target instanceof PsiMember member) || target instanceof PsiTypeParameter || members.contains(member)
                        || PsiTreeUtil.getParentOfType(reference, PsiAnnotation.class) != null || !isProjectCode(member, file)) {
                    continue;
                }
                // The class of a copied member, as in Kernels.scale(...): the harness drops it
                if (target instanceof PsiClass && reference.getParent() instanceof PsiReferenceExpression parent
                        && parent.getQualifier() == reference && members.contains(parent.resolve())) {
                    continue;
                }
                used.add(reference.getParent() instanceof PsiReferenceExpression parent && parent.getQualifier() == reference
                        ? parent.getText() : reference.getText());
            }
        }
        return List.copyOf(used);
    }

    // 'code' is the part of 'member' that runs: a method's body, a field's initializer
    private static void check(PsiMember member, PsiElement code, String where, PsiFile file, Set<String> problems) {
        if (UNICODE_ESCAPE.matcher(member.getText()).find()) {
            problems.add("Do not use Unicode escapes (`\\uXXXX`) in " + where + ".");
        }
        if (code == null) {
            return;
        }
        if (!PsiTreeUtil.findChildrenOfType(code, PsiClass.class).isEmpty()) {
            problems.add("Do not declare classes, local or anonymous, in " + where + ".");
        }
        if (!PsiTreeUtil.findChildrenOfAnyType(code, PsiLambdaExpression.class, PsiMethodReferenceExpression.class).isEmpty()) {
            problems.add("Do not use lambdas or method references in " + where + ".");
        }
        for (PsiClassObjectAccessExpression literal : PsiTreeUtil.findChildrenOfType(code, PsiClassObjectAccessExpression.class)) {
            problems.add("Do not use `" + literal.getText() + "` in " + where + ".");
        }
        // A kernel has no use for text, and a string could only be an argument to code that should not run
        for (PsiLiteralExpression literal : PsiTreeUtil.findChildrenOfType(code, PsiLiteralExpression.class)) {
            PsiType type = literal.getType();
            if (type != null && type.equalsToText(CommonClassNames.JAVA_LANG_STRING)
                    && PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class) == null) {
                problems.add("Do not use strings in " + where + ".");
            }
        }
        for (PsiJavaCodeReferenceElement reference : PsiTreeUtil.findChildrenOfType(code, PsiJavaCodeReferenceElement.class)) {
            // Fails closed: a name the PSI cannot resolve to one valid target is not run, whatever javac makes of it
            JavaResolveResult resolved = reference.advancedResolve(false);
            if (PsiTreeUtil.getParentOfType(reference, PsiAnnotation.class) != null
                    || resolved.isValidResult() && allowed(resolved.getElement(), file)) {
                continue;
            }
            // An allowed method called with the wrong arguments: say so, or the model drops a method it may use
            PsiElement candidate = resolved.getElement() != null ? resolved.getElement()
                    : reference.advancedResolve(true).getElement();
            if (candidate instanceof PsiMethod method && method.getContainingClass() != null && allowed(method, file)) {
                problems.add("`" + reference.getText() + "` in " + where + " is called with arguments that match none of the `"
                        + method.getName() + "` methods of `" + method.getContainingClass().getName() + "`, which take: "
                        + signatures(method.getContainingClass(), method.getName()) + ".");
            } else {
                problems.add("The kernel and the methods it calls may only use TornadoVM's kernel API (`KernelContext`, "
                        + "`TileContext`, its types and `TornadoMath`), `Math`, the primitive wrappers (`Float`, `Integer`, ...) "
                        + "and the project's own code: `" + reference.getText() + "` in " + where + " is not allowed.");
            }
        }
    }

    // The parameter types of the methods named {@code name}, e.g. "scale(Tile, double)"; the first few
    private static String signatures(PsiClass owner, String name) {
        List<String> signatures = new ArrayList<>();
        for (PsiMethod method : owner.findMethodsByName(name, true)) {
            List<String> types = new ArrayList<>();
            for (var parameter : method.getParameterList().getParameters()) {
                types.add(parameter.getType().getPresentableText());
            }
            signatures.add(name + "(" + String.join(", ", types) + ")");
        }
        return String.join("; ", signatures.subList(0, Math.min(signatures.size(), 8)));
    }

    // Locals, parameters and packages pass; a class or a member passes if its class does
    private static boolean allowed(PsiElement target, PsiFile file) {
        if (target == null) {
            return false;
        }
        PsiClass owner = target instanceof PsiClass psiClass ? psiClass
                : target instanceof PsiMember member ? member.getContainingClass() : null;
        if (owner == null) {
            return !(target instanceof PsiMember);
        }
        if (PsiUtil.isArrayClass(owner)) {
            return target instanceof PsiField field && field.getName().equals("length");
        }
        String name = owner.getQualifiedName();
        if (name != null && (JAVA_LANG.contains(name) || name.equals(KERNEL_CONTEXT)
                || DEVICE_PACKAGES.stream().anyMatch(name::startsWith))) {
            return true;
        }
        return isProjectCode(owner, file);
    }

    // A class, or a member of one, of the project's sources (or of the kernel's file, a copy)
    private static boolean isProjectCode(PsiMember member, PsiFile file) {
        PsiClass owner = member instanceof PsiClass psiClass ? psiClass : member.getContainingClass();
        if (owner == null) {
            return false;
        }
        String name = owner.getQualifiedName();
        // No project can define classes there, whatever its sources contain
        if (name != null && (name.startsWith(KernelReplacement.TORNADO_API) || JDK_PACKAGES.stream().anyMatch(name::startsWith))) {
            return false;
        }
        PsiFile ownerFile = owner.getContainingFile();
        if (ownerFile == null) {
            return false;
        }
        if (ownerFile == file) {
            return true;
        }
        VirtualFile virtualFile = ownerFile.getOriginalFile().getVirtualFile();
        return virtualFile != null && ProjectFileIndex.getInstance(owner.getProject()).isInSourceContent(virtualFile);
    }
}
