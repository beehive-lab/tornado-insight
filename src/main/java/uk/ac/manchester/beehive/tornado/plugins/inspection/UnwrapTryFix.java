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

package uk.ac.manchester.beehive.tornado.plugins.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes a {@code try} statement and its {@code catch} blocks, keeping the statements of the
 * {@code try} block followed by those of the {@code finally} block. They replace the statement
 * in its block; if a variable they declare would clash with one declared later in that block,
 * or the statement is not in a block, they are kept in a block of their own.
 * Not offered for try-with-resources, whose resources are objects a kernel cannot use anyway.
 */
final class UnwrapTryFix implements LocalQuickFix {

    /** Whether the fix applies to {@code statement}. */
    static boolean isApplicable(@NotNull PsiTryStatement statement) {
        return statement.getResourceList() == null && statement.getTryBlock() != null;
    }

    @Override
    public @NotNull String getFamilyName() {
        return MessageBundle.message("quickfix.unwrapTry");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        if (!(descriptor.getPsiElement() instanceof PsiTryStatement statement) || !isApplicable(statement)) {
            return;
        }
        List<PsiStatement> kept = new ArrayList<>(List.of(statement.getTryBlock().getStatements()));
        if (statement.getFinallyBlock() != null) {
            kept.addAll(List.of(statement.getFinallyBlock().getStatements()));
        }
        PsiElement parent = statement.getParent();
        if (parent instanceof PsiCodeBlock block && !clashes(kept, block, statement)) {
            for (PsiStatement keep : kept) {
                block.addBefore(keep, statement);
            }
            statement.delete();
            return;
        }
        StringBuilder text = new StringBuilder("{\n");
        for (PsiStatement keep : kept) {
            text.append(keep.getText()).append('\n');
        }
        statement.replace(JavaPsiFacade.getElementFactory(project).createStatementFromText(text.append('}').toString(), statement));
    }

    // Whether a variable declared by the kept statements is declared again after the try
    private static boolean clashes(List<PsiStatement> kept, PsiCodeBlock block, PsiTryStatement statement) {
        Set<String> names = new HashSet<>();
        for (PsiStatement keep : kept) {
            names.addAll(declared(keep));
        }
        if (names.isEmpty()) {
            return false;
        }
        for (PsiElement next = statement.getNextSibling(); next != null; next = next.getNextSibling()) {
            for (PsiLocalVariable variable : PsiTreeUtil.findChildrenOfType(next, PsiLocalVariable.class)) {
                if (names.contains(variable.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> declared(@Nullable PsiStatement statement) {
        Set<String> names = new HashSet<>();
        if (statement instanceof PsiDeclarationStatement declaration) {
            for (PsiElement element : declaration.getDeclaredElements()) {
                if (element instanceof PsiLocalVariable variable) {
                    names.add(variable.getName());
                }
            }
        }
        return names;
    }
}
