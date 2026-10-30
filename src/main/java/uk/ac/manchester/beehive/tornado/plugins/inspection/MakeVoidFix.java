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
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiUnaryExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.List;

/**
 * Makes a kernel {@code void}: changes the return type and drops the value of each of its
 * {@code return} statements. A returned call, assignment, increment or {@code new} is kept as a
 * statement of its own, so its side effects stay. A {@code return} left as the last statement
 * of the body is removed.
 */
final class MakeVoidFix implements LocalQuickFix {

    @Override
    public @NotNull String getFamilyName() {
        return MessageBundle.message("quickfix.makeVoid");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        if (!(descriptor.getPsiElement() instanceof PsiTypeElement returnType)
                || !(returnType.getParent() instanceof PsiMethod method) || method.getBody() == null) {
            return;
        }
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        List<PsiReturnStatement> returns = PsiTreeUtil.findChildrenOfType(method.getBody(), PsiReturnStatement.class).stream()
                .filter(statement -> PsiTreeUtil.getParentOfType(statement, PsiMethod.class, PsiLambdaExpression.class, PsiClass.class) == method)
                .toList();
        for (PsiReturnStatement statement : returns) {
            PsiExpression value = statement.getReturnValue();
            if (value == null) {
                continue;
            }
            if (hasSideEffects(value) && statement.getParent() instanceof PsiCodeBlock) {
                statement.getParent().addBefore(factory.createStatementFromText(value.getText() + ";", statement), statement);
            }
            statement.replace(factory.createStatementFromText("return;", statement));
        }
        returnType.replace(factory.createTypeElement(PsiTypes.voidType()));
        PsiStatement[] statements = method.getBody().getStatements();
        if (statements.length > 0 && statements[statements.length - 1] instanceof PsiReturnStatement last
                && last.getReturnValue() == null) {
            last.delete();
        }
    }

    // Expressions that may be written as a statement on their own
    private static boolean hasSideEffects(PsiExpression value) {
        return value instanceof PsiMethodCallExpression || value instanceof PsiAssignmentExpression
                || value instanceof PsiNewExpression || value instanceof PsiUnaryExpression unary
                && (unary.getOperationTokenType() == JavaTokenType.PLUSPLUS || unary.getOperationTokenType() == JavaTokenType.MINUSMINUS);
    }
}
