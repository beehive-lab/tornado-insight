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

package uk.ac.manchester.beehive.tornado.plugins.kernel;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiForStatement;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Where {@code @Parallel} may go: only on the index variable that a {@code for} loop declares.
 * javac accepts it on a field, a parameter or any local variable (its targets include
 * {@code LOCAL_VARIABLE} and {@code TYPE_USE}), but TornadoVM only parallelises loops.
 */
public final class ParallelPlacement {

    private ParallelPlacement() {
    }

    /**
     * Returns the {@code @Parallel} annotations in {@code method}, including those on the
     * method itself and its parameters, that are not on a {@code for} loop's index variable.
     */
    public static @NotNull List<PsiAnnotation> misplaced(@NotNull PsiMethod method) {
        List<PsiAnnotation> misplaced = new ArrayList<>();
        for (PsiAnnotation annotation : PsiTreeUtil.findChildrenOfType(method, PsiAnnotation.class)) {
            if (isParallel(annotation) && !isOnLoopIndex(annotation)) {
                misplaced.add(annotation);
            }
        }
        return misplaced;
    }

    /**
     * Fixes the placement where the intent is clear: removes every misplaced {@code @Parallel}
     * and, if no loop is {@code @Parallel} then, annotates the index of the method's only
     * outermost {@code for} loop. Changes {@code method}, so it needs write access unless the
     * method is in a non-physical copy.
     *
     * @return whether {@code method} changed
     */
    public static boolean repair(@NotNull PsiMethod method) {
        List<PsiAnnotation> misplaced = misplaced(method);
        if (misplaced.isEmpty()) {
            return false;
        }
        String name = misplaced.get(0).getNameReferenceElement() == null ? "Parallel"
                : misplaced.get(0).getNameReferenceElement().getText();
        for (PsiAnnotation annotation : misplaced) {
            annotation.delete();
        }
        if (!hasParallelLoop(method)) {
            PsiLocalVariable index = onlyOutermostLoopIndex(method);
            if (index != null && index.getModifierList() != null) {
                index.getModifierList().addAnnotation(name);
            }
        }
        return true;
    }

    /** Whether {@code annotation} is TornadoVM's {@code @Parallel}. */
    public static boolean isParallel(@NotNull PsiAnnotation annotation) {
        PsiClass type = annotation.resolveAnnotationType();
        if (type != null) {
            return TornadoKernels.PARALLEL.equals(type.getQualifiedName());
        }
        String name = annotation.getQualifiedName();
        return name != null && (name.equals("Parallel") || name.equals(TornadoKernels.PARALLEL));
    }

    private static boolean hasParallelLoop(PsiMethod method) {
        for (PsiAnnotation annotation : PsiTreeUtil.findChildrenOfType(method, PsiAnnotation.class)) {
            if (isParallel(annotation) && isOnLoopIndex(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnLoopIndex(PsiAnnotation annotation) {
        PsiElement list = annotation.getParent();
        if (!(list instanceof PsiModifierList)) {
            return false;
        }
        PsiModifierListOwner owner = PsiTreeUtil.getParentOfType(list, PsiModifierListOwner.class);
        return owner instanceof PsiLocalVariable variable && loopDeclaring(variable) != null;
    }

    private static @Nullable PsiForStatement loopDeclaring(PsiLocalVariable variable) {
        if (!(variable.getParent() instanceof PsiDeclarationStatement declaration)) {
            return null;
        }
        PsiElement loop = declaration.getParent();
        return loop instanceof PsiForStatement forStatement && forStatement.getInitialization() == declaration
                ? forStatement : null;
    }

    private static @Nullable PsiLocalVariable onlyOutermostLoopIndex(PsiMethod method) {
        List<PsiForStatement> outermost = new ArrayList<>();
        for (PsiForStatement loop : PsiTreeUtil.findChildrenOfType(method, PsiForStatement.class)) {
            if (PsiTreeUtil.getParentOfType(loop, PsiForStatement.class, true, PsiMethod.class, PsiClass.class) == null) {
                outermost.add(loop);
            }
        }
        if (outermost.size() != 1
                || !(outermost.get(0).getInitialization() instanceof PsiDeclarationStatement declaration)
                || declaration.getDeclaredElements().length != 1
                || !(declaration.getDeclaredElements()[0] instanceof PsiLocalVariable index)
                || !PsiTypes.intType().equals(index.getType())) {
            return null;
        }
        return index;
    }
}
