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
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

/**
 * Removes the statement a problem is reported on: the statement itself, or the
 * expression statement whose whole expression it is (e.g. a call).
 * <p>
 * A statement that is the body of an {@code if}, a loop or a label, rather than
 * one of a block's statements, is replaced with an empty block, so the code still
 * compiles.
 * </p>
 */
final class RemoveStatementFix implements LocalQuickFix {

    private final String nameKey;

    /**
     * @param nameKey the bundle key of the fix's name, e.g. "Remove 'assert' statement"
     */
    RemoveStatementFix(@NotNull String nameKey) {
        this.nameKey = nameKey;
    }

    /**
     * Returns the statement the fix removes for a problem on {@code element}, or
     * {@code null} if there is none: {@code element} is an expression whose value
     * is used, not a statement of its own.
     */
    static @Nullable PsiStatement statementOf(@NotNull PsiElement element) {
        if (element instanceof PsiStatement statement) {
            return statement;
        }
        if (element.getParent() instanceof PsiExpressionStatement statement && statement.getExpression() == element) {
            return statement;
        }
        return null;
    }

    @Override
    public @NotNull String getFamilyName() {
        return MessageBundle.message(nameKey);
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiStatement statement = statementOf(descriptor.getPsiElement());
        if (statement == null) {
            return;
        }
        if (statement.getParent() instanceof PsiCodeBlock) {
            statement.delete();
        } else {
            statement.replace(JavaPsiFacade.getElementFactory(project).createStatementFromText("{}", statement));
        }
    }
}
