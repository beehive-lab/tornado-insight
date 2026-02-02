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

package uk.ac.manchester.beehive.tornado.plugins.inspector;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;

/**
 * A custom inspection tool to check for non-void return types in methods that are
 * annotated with "Parallel" or "Reduce", or that have a KernelContext parameter.
 * <p>
 * Methods registered as kernel entry points via .task() must return void. However,
 * non-void return types are valid when the method is inlined as a helper, so this
 * inspection registers a WARNING rather than an ERROR.
 * </p>
 */
public class NonVoidReturnInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        HashSet<PsiMethod> reportedMethods = new HashSet<>();
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    if (parent == null) return;
                    checkReturnType(parent);
                }
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);

                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    PsiType type = parameter.getType();
                    if (type.getCanonicalText().endsWith("KernelContext")) {
                        checkReturnType(method);
                        break;
                    }
                }
            }

            private void checkReturnType(PsiMethod method) {
                if (reportedMethods.contains(method)) return;
                reportedMethods.add(method);

                PsiType returnType = method.getReturnType();
                if (returnType != null && !PsiTypes.voidType().equals(returnType)) {
                    PsiTypeElement returnTypeElement = method.getReturnTypeElement();
                    if (returnTypeElement != null) {
                        holder.registerProblem(returnTypeElement,
                                MessageBundle.message("inspection.nonVoidReturn"),
                                ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
                    }
                }
            }
        };
    }
}
