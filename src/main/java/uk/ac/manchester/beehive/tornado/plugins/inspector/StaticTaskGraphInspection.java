/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.inspector;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

public class StaticTaskGraphInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Constructs and returns a `PsiElementVisitor` that inspects Java code
     * for the problematic static usages of `TaskGraph`.
     *
     * @param holder     Collects problems detected during inspection.
     * @param isOnTheFly Indicates whether the inspection is done on-the-fly, i.e., as you type.
     * @return A visitor to inspect Java code elements.
     */
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolve = expression.resolve();
                // Check if the resolved reference is a field and has the type "TaskGraph"
                if (resolve instanceof PsiField field) {
                    PsiClass containingClass = field.getContainingClass();
                    PsiType type = field.getType();
                    // Ensure that the field is of type "TaskGraph" and is static
                    if (containingClass != null && "uk.ac.manchester.tornado.api.TaskGraph".equals(type.getCanonicalText())) {
                        // If so, register a problem since static TaskGraph is not desired.
                        if (field.hasModifierProperty(PsiModifier.STATIC)) {
                            holder.registerProblem(expression,
                                    MessageBundle.message("inspection.staticTaskGraph"), ProblemHighlightType.ERROR);
                        }
                    }
                }
            }

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiReferenceExpression methodExpression = expression.getMethodExpression();
                PsiElement resolve = methodExpression.resolve();
                // Check if the method being called belongs to the "TaskGraph" class and has the name "task"
                if (resolve instanceof PsiMethod method) {
                    PsiClass containingClass = method.getContainingClass();
                    // Ensure that the method belongs to "TaskGraph" and has the name "task" and is static
                    if (containingClass != null
                            && "uk.ac.manchester.tornado.api.TaskGraph".equals(containingClass.getQualifiedName())
                            && "task".equals(method.getName())) {
                        // If so, register a problem since static task method in TaskGraph is not desired.
                        if (method.hasModifierProperty(PsiModifier.STATIC)) {
                            holder.registerProblem(expression,
                                    MessageBundle.message("inspection.staticTaskGraph"),ProblemHighlightType.ERROR);
                        }
                    }
                }
            }
        };
    }
}
