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

import java.util.HashSet;
import java.util.Objects;

/**
 * A custom inspection tool to check for thrown exceptions within methods annotated with
 * "Parallel" or "Reduce" as TornadoVM does not support for traps/exceptions.
 * <p>
 * This inspection tool ensures that:
 * - No exceptions are thrown within the method body.
 * - The method body does not contain any try/catch blocks.
 * - No exceptions are declared to be thrown in the method's signature.
 * </p>
 */
public class ThrowInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Builds the visitor used for the inspection.
     *
     * @param holder The container which receives the problems found during the inspection.
     * @param isOnTheFly Whether this inspection is being run on-the-fly, as the user types, or as a batch process.
     * @return The visitor instance for analyzing code constructs.
     */
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        HashSet<PsiThrowStatement> reportedStatement = new HashSet<>();
        HashSet<PsiMethod> reportedMethod = new HashSet<>();
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    if (parent == null) return;
                    checkThrow(parent);
                }
            }

            @Override
            public void visitMethod(PsiMethod method) {
                super.visitMethod(method);

                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    PsiType type = parameter.getType();
                    if (type.getCanonicalText().equals("KernelContext")) {
                        checkThrow(method);
                        break;
                    }
                }
            }


            private void checkThrow(PsiMethod method) {
                method.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitThrowStatement(PsiThrowStatement statement) {
                        super.visitThrowStatement(statement);
                        if (!reportedStatement.contains(statement)) {
                            ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), method);
                            holder.registerProblem(statement,
                                    MessageBundle.message("inspection.traps.throw"),
                                    ProblemHighlightType.ERROR);
                            reportedStatement.add(statement);
                        }
                    }
                    @Override
                    public void visitTryStatement(PsiTryStatement statement) {
                        super.visitTryStatement(statement);
                        ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), method);
                        holder.registerProblem(statement,
                                MessageBundle.message("inspection.traps.tryCatch"),
                                ProblemHighlightType.ERROR);
                    }
                });


                // Checking the method signature for thrown exceptions
                if (!reportedMethod.contains(method)) {
                    for (PsiClassType exception : method.getThrowsList().getReferencedTypes()) {
                        holder.registerProblem(method.getThrowsList(),
                                MessageBundle.message("inspection.traps.throws")+ "\n" + exception.getCanonicalText(),
                                ProblemHighlightType.ERROR);
                        ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), method);
                    }
                    reportedMethod.add(method);
                }
            }
        };
    }
}
