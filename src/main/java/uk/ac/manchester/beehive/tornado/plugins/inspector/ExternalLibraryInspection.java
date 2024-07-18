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
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The `ExternalLibraryInspection` class checks for external library method
 * calls within methods annotated with "@Parallel" or "@Reduce".
 *
 * <p>Methods annotated with these annotations are expected to avoid external
 * library calls unless they are specifically whitelisted. Any detected
 * usage of unsupported libraries will be highlighted as a warning in the IDE.</p>
 */
public class ExternalLibraryInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Constructs and returns a `PsiElementVisitor` to inspect Java annotations for
     * external library method calls.
     *
     * @param holder     The collector for the problems found.
     * @param isOnTheFly Indicates whether the inspection is being run on-the-fly
     *                   while the user is editing.
     * @return A visitor to traverse and inspect Java elements.
     */
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                // Check if the annotation is of type 'Parallel' or 'Reduce'
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    if (method == null) return;
                    // Visit all elements inside the method to check for external library method calls
                    method.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                            super.visitMethodCallExpression(expression);
                            if (expression.getMethodExpression().resolve() != null) {
                                PsiMethod calledMethod = (PsiMethod) expression.getMethodExpression().resolve();
                                // Check if the called method's qualified name does not belong to
                                // whitelisted libraries/packages.
                                if (calledMethod != null){
                                    String qualifiedName = Objects.requireNonNull(calledMethod.getContainingClass()).getQualifiedName();
                                    if (qualifiedName != null && !qualifiedName.startsWith("java.") &&
                                            !qualifiedName.startsWith("uk.ac.manchester.tornado")&&
                                            !qualifiedName.startsWith("_Dummy_"))
                                    {
                                        ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), method);
                                        holder.registerProblem(expression,
                                                MessageBundle.message("inspection.externalLibrary"),
                                                ProblemHighlightType.WARNING);
                                    }
                                }
                            }
                        }
                    });

                }
            }
        };
    }
}
