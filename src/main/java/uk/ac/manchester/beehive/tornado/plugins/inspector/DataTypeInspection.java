/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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

/**
 * This inspection tool identifies and highlights unsupported data types
 * within methods that are annotated with either "@Parallel" or "@Reduce".
 *
 * <p>Methods with either of these annotations should use data types that are
 * supported by the TornadoVM. Any usage of unsupported data types will be
 * highlighted as an error in the IDE.</p>
 *
 * <p>The inspection follows method calls across class boundaries, analyzing all
 * helper methods that TornadoVM would inline at runtime.</p>
 */
public class DataTypeInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Constructs and returns a PsiElementVisitor that checks Java annotations for
     * specific criteria and highlights problematic data types.
     *
     * @param holder     Holds the problems identified during inspection.
     * @param isOnTheFly Indicates whether the inspection is being run on-the-fly
     *                   while the user is editing.
     * @return A visitor to traverse and inspect Java elements.
     */
    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                if (!KernelCallGraphAnalyzer.isKernelAnnotation(annotation)) return;

                PsiMethod kernelMethod = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                if (kernelMethod == null) return;

                KernelCallGraphAnalyzer.AnalysisScope scope =
                        KernelCallGraphAnalyzer.resolve(kernelMethod);

                for (PsiMethod method : scope.getAnalyzableMethods()) {
                    String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
                    method.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitLocalVariable(PsiLocalVariable variable) {
                            checkVariable(variable.getType(), variable, context);
                        }

                        @Override
                        public void visitField(PsiField field) {
                            checkVariable(field.getType(), field, context);
                        }

                        @Override
                        public void visitParameter(PsiParameter parameter) {
                            checkVariable(parameter.getType(), parameter, context);
                        }
                    });
                }

                for (var entry : scope.getNonAnalyzableCallSites().entrySet()) {
                    holder.registerProblem(entry.getKey(),
                            MessageBundle.message("inspection.helper.unresolvable")
                                    + ": " + entry.getValue(),
                            ProblemHighlightType.WEAK_WARNING);
                }
            }

            /**
             * Checks the type of the provided variable. If the type is unsupported,
             * the variable is registered as a problem.
             *
             * @param type     The type of the variable to be checked.
             * @param variable The variable itself.
             * @param context  Helper context string for diagnostics.
             */
            private void checkVariable(PsiType type, PsiVariable variable, String context) {
                if (!type.equalsToText("int") && !type.equalsToText("boolean") && !type.equalsToText("double")
                        && !type.equalsToText("long") && !type.equalsToText("char") && !type.equalsToText("float")
                        && !type.equalsToText("byte") && !type.equalsToText("short")
                        && !type.getCanonicalText().startsWith("int[]") && !type.getCanonicalText().startsWith("boolean[]")
                        && !type.getCanonicalText().startsWith("double[]") && !type.getCanonicalText().startsWith("long[]")
                        && !type.getCanonicalText().startsWith("char[]") && !type.getCanonicalText().startsWith("float[]")
                        && !type.getCanonicalText().startsWith("byte[]") && !type.getCanonicalText().startsWith("short[]")
                        && !type.equalsToText("Int3") && !type.getCanonicalText().startsWith("uk.ac.manchester.tornado.api.")) {
                    PsiMethod parentMethod = PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
                    if (parentMethod != null) {
                        ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), parentMethod);
                    }
                    holder.registerProblem(
                            variable,
                            MessageBundle.message("inspection.datatype") + context,
                            ProblemHighlightType.ERROR
                    );
                }
            }
        };
    }
}
