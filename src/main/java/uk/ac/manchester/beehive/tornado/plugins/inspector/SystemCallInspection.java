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
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.entity.ProblemMethods;
import uk.ac.manchester.beehive.tornado.plugins.entity.RestrictedClasses;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A custom inspection tool to check for invocations of potentially problematic system and external methods
 * within methods annotated with either "Parallel" or "Reduce".
 * <p>
 * This inspection tool searches for:
 * - Calls to native methods
 * - Calls to methods from various Java system and utility classes that may lead to unexpected behavior
 *   or resource usage in parallel or reduced contexts.
 * </p>
 */
public class SystemCallInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Builds the visitor used for the inspection.
     *
     * @param holder The container which receives the problems found during the inspection.
     * @param isOnTheFly Whether this inspection is being run on-the-fly, as the user types, or as a batch process.
     * @return The visitor instance for analyzing code constructs.
     */
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    if (parent == null) return;
                    parent.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                            super.visitMethodCallExpression(expression);
                            PsiMethod method = expression.resolveMethod();
                            if (method != null && method.hasModifierProperty(PsiModifier.NATIVE)) {
                                // This method call is invoking a native method.
                                // Handle or report as necessary.
                                ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), parent);
                                holder.registerProblem(expression,
                                        MessageBundle.message("inspection.nativeCall"),
                                        ProblemHighlightType.ERROR);
                            }
                            if (method == null) return;
                            String className = Objects.requireNonNull(method.getContainingClass()).getQualifiedName();

                            // Checking for method calls from potentially problematic system and utility classes.
                            assert className != null;
                            if (RestrictedClasses.isRestrictedClass(className)) {
                                ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), parent);
                                holder.registerProblem(expression,
                                        MessageBundle.message("inspection.external"),
                                        ProblemHighlightType.ERROR);
                            }

                        }
                    });
                }
            }
        };
    }
}
