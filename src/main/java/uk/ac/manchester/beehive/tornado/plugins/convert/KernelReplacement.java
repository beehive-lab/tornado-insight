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

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParserFacade;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.kernel.ParallelPlacement;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Puts the kernel from a model's answer in the original method's place, or after it under a name
 * of its own, moves a misplaced {@code @Parallel}, imports the TornadoVM types it names, and
 * formats it. The preview runs it on a copy of the file, and Accept runs it again on the file
 * itself, so both give the same text.
 */
final class KernelReplacement {

    static final String TORNADO_API = TornadoKernels.TORNADO_API;

    /** Where the kernel goes. */
    enum Placement {
        /** In place of the method, with its name and doc comment; its callers now call the kernel. */
        REPLACE,
        /** After the method, as {@code <name>Kernel}; the method and its callers stay as they are. */
        BESIDE
    }

    /**
     * @param kernel    the kernel in the file
     * @param repaired  whether a misplaced {@code @Parallel} was moved to the loop index
     * @param docCopied whether the kernel's doc comment is the original's, copied
     */
    record Replaced(@NotNull PsiMethod kernel, boolean repaired, boolean docCopied) {

        /** The kernel as the model wrote it, after the repair and formatting, without a copied doc comment. */
        @NotNull String kernelText() {
            PsiDocComment doc = kernel.getDocComment();
            String text = kernel.getText();
            return docCopied && doc != null ? text.substring(doc.getStartOffsetInParent() + doc.getTextLength()).stripLeading() : text;
        }
    }

    private KernelReplacement() {
    }

    /**
     * The text of {@code original}'s file with the kernel in {@code code} placed as
     * {@code placement} says; the file itself is not changed. Must be called with read access.
     *
     * @return the text, or {@code null} if {@code code} declares no method
     */
    static @Nullable String preview(@NotNull PsiMethod original, @NotNull String code, @NotNull Placement placement) {
        PsiFile copy = (PsiFile) original.getContainingFile().copy();
        return place(PsiTreeUtil.findSameElementInCopy(original, copy), code, placement) == null ? null : copy.getText();
    }

    /** The name {@link Placement#BESIDE} gives the kernel: {@code <name>Kernel}, numbered if the class has one. */
    static @NotNull String besideName(@NotNull PsiMethod original) {
        PsiClass owner = original.getContainingClass();
        String name = original.getName() + "Kernel";
        for (int i = 2; owner != null && owner.findMethodsByName(name, false).length > 0; i++) {
            name = original.getName() + "Kernel" + i;
        }
        return name;
    }

    /** {@link #place} with {@link Placement#REPLACE}. */
    static @Nullable Replaced replace(@NotNull PsiMethod original, @NotNull String code) {
        return place(original, code, Placement.REPLACE);
    }

    /**
     * Puts the method of the same name in {@code code} (or its only method) where
     * {@code placement} says. The kernel gets the original's access modifier and, in its place,
     * its name and its doc comment if the answer has none. Needs write access unless
     * {@code original} is in a non-physical copy.
     *
     * @return the kernel in the file, or {@code null} if {@code code} declares no method
     */
    static @Nullable Replaced place(@NotNull PsiMethod original, @NotNull String code, @NotNull Placement placement) {
        PsiClass owner = original.getContainingClass();
        if (owner == null) {
            return null;
        }
        Project project = original.getProject();
        PsiMethod kernel = pick(parse(original, code), original.getName());
        if (kernel == null) {
            return null;
        }
        String name = placement == Placement.REPLACE ? original.getName() : besideName(original);
        if (!kernel.getName().equals(name)) {
            kernel.setName(name);
        }
        PsiUtil.setModifierProperty(kernel, PsiUtil.getAccessModifier(PsiUtil.getAccessLevel(original.getModifierList())), true);
        // Before the imports and the formatting, which then apply to the moved annotation too
        boolean repaired = ParallelPlacement.repair(kernel);
        PsiDocComment doc = placement == Placement.REPLACE && kernel.getDocComment() == null && original.getDocComment() != null
                ? (PsiDocComment) original.getDocComment().copy() : null;
        if (doc != null) {
            kernel.addBefore(doc.copy(), kernel.getFirstChild());
        }
        PsiMethod replaced = (PsiMethod) (placement == Placement.REPLACE ? original.replace(kernel) : owner.addAfter(kernel, original));
        importTornadoTypes(replaced);
        replaced = (PsiMethod) JavaCodeStyleManager.getInstance(project).shortenClassReferences(replaced);
        replaced = (PsiMethod) CodeStyleManager.getInstance(project).reformat(replaced);
        if (doc != null && replaced.getDocComment() != null) {
            // The formatter may reflow it; the user's comment stays as it was
            replaced.getDocComment().replace(doc);
        }
        separateImports(replaced.getContainingFile());
        return new Replaced(replaced, repaired, doc != null);
    }

    private static List<PsiMethod> parse(PsiMethod context, String code) {
        // The model may add imports or a package line although it is asked not to
        String body = code.lines()
                .filter(line -> !line.strip().startsWith("import ") && !line.strip().startsWith("package "))
                .collect(Collectors.joining("\n"));
        PsiClass holder = JavaPsiFacade.getElementFactory(context.getProject()).createClassFromText(body, context);
        List<PsiMethod> methods = new ArrayList<>(List.of(holder.getMethods()));
        for (PsiClass wrapper : holder.getInnerClasses()) {
            // ... or wrap the method in a class
            methods.addAll(List.of(wrapper.getMethods()));
        }
        return methods;
    }

    private static @Nullable PsiMethod pick(List<PsiMethod> methods, String name) {
        for (PsiMethod method : methods) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        List<PsiMethod> candidates = methods.stream().filter(m -> !m.getName().equals("main")).toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    // Imports the TornadoVM API class for every simple name that does not resolve yet and that
    // names exactly one class of the API
    private static void importTornadoTypes(PsiMethod method) {
        PsiFile file = method.getContainingFile();
        if (!(file instanceof PsiJavaFile javaFile)) {
            return;
        }
        PsiShortNamesCache names = PsiShortNamesCache.getInstance(method.getProject());
        for (PsiJavaCodeReferenceElement reference : PsiTreeUtil.findChildrenOfType(method, PsiJavaCodeReferenceElement.class)) {
            if (reference.isQualified() || reference.getReferenceName() == null || reference.resolve() != null
                    || !isTypePosition(reference)) {
                continue;
            }
            List<PsiClass> api = new ArrayList<>();
            for (PsiClass candidate : names.getClassesByName(reference.getReferenceName(), file.getResolveScope())) {
                String qualifiedName = candidate.getQualifiedName();
                if (qualifiedName != null && qualifiedName.startsWith(TORNADO_API)) {
                    api.add(candidate);
                }
            }
            if (api.size() == 1) {
                javaFile.importClass(api.get(0));
            }
        }
    }

    // In a non-physical copy, adding the first import leaves no whitespace around the import
    // list, and the formatter does not add it; the editor's file gets a blank line on each side
    private static void separateImports(PsiFile file) {
        if (!(file instanceof PsiJavaFile javaFile) || javaFile.getImportList() == null
                || javaFile.getImportList().getTextLength() == 0) {
            return;
        }
        PsiImportList imports = javaFile.getImportList();
        PsiParserFacade parser = PsiParserFacade.getInstance(file.getProject());
        if (imports.getNextSibling() != null && !(imports.getNextSibling() instanceof PsiWhiteSpace)) {
            file.addAfter(parser.createWhiteSpaceFromText("\n\n"), imports);
        }
        if (imports.getPrevSibling() != null && !(imports.getPrevSibling() instanceof PsiWhiteSpace)) {
            file.addBefore(parser.createWhiteSpaceFromText("\n\n"), imports);
        }
    }

    // A reference that can name a class: a type, an annotation, or the qualifier of a call
    // such as TornadoMath.sqrt(x)
    private static boolean isTypePosition(PsiJavaCodeReferenceElement reference) {
        PsiElement parent = reference.getParent();
        return !(reference instanceof PsiReferenceExpression)
                || parent instanceof PsiReferenceExpression qualified && qualified.getQualifier() == reference;
    }
}
