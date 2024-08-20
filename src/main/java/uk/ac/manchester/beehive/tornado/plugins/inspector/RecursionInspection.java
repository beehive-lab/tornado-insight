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
import java.util.Set;

/**
 * The `RecursionInspection` class checks for recursive calls within methods
 * annotated with "@Parallel" or "@Reduce".
 *
 * <p>Methods annotated with these annotations are expected to avoid recursive
 * calls. Any detected recursive call will be highlighted as an error in the IDE.</p>
 */
public class RecursionInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Constructs and returns a `PsiElementVisitor` to inspect Java annotations for
     * recursive calls.
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
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    if (parent == null) return;
                    checkRecursion(parent);
                }
            }

            @Override
            public void visitMethod(PsiMethod method) {
                super.visitMethod(method);

                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    PsiType type = parameter.getType();
                    if (type.getCanonicalText().equals("KernelContext")) {
                        checkRecursion(method);
                        break;
                    }
                }
            }

            private void checkRecursion(PsiMethod method) {
                    // Visit all elements inside the method to check for recursive calls
                    method.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitCallExpression(PsiCallExpression callExpression) {
                            super.visitCallExpression(callExpression);
                            PsiMethod calledMethod = callExpression.resolveMethod();
                            Set<PsiMethod> visited = new HashSet<>();
                            if (isRecursive(calledMethod, visited)) {
                                if (calledMethod == null) return;
                                ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), calledMethod);
                                holder.registerProblem(
                                        calledMethod,
                                        MessageBundle.message("inspection.recursion"),
                                        ProblemHighlightType.ERROR);
                            }
                        }
                    });
                }
        };
    }

    /**
     * The method detects whether the input method has a recursive call.
     * It traverses the input method's body and adds the method call into the set.
     * If the same method is added to the set again, it indicates a recursive call.
     *
     * @param method  The input method to be checked.
     * @param visited A set that stores the method call chain.
     * @return a boolean value that indicates if the input method has a recursive call.
     */
    private boolean isRecursive(PsiMethod method, Set<PsiMethod> visited) {
        if (!visited.add(method)) {
            return true;
        }
        if (method == null) return false;
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
                PsiMethod calledMethod = call.resolveMethod();
                if (calledMethod != null && isRecursive(calledMethod, visited)) {
                    return true;
                }
            }
        }
        visited.remove(method);
        return false;
    }
}
