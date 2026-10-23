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

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.*;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

/**
 * The `ExternalLibraryInspection` class checks for external library method
 * calls within TornadoVM kernels (see {@link TornadoKernels}).
 *
 * <p>Kernels are expected to avoid external
 * library calls unless they are specifically whitelisted. Any detected
 * usage of unsupported libraries will be highlighted as a warning in the IDE.</p>
 *
 * <p>The inspection follows method calls across class boundaries, analyzing all
 * helper methods that TornadoVM would inline at runtime.</p>
 */
public class ExternalLibraryInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (PsiMethod method : scope.getAnalyzableMethods()) {
            String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    if (!(expression.getMethodExpression().resolve() instanceof PsiMethod calledMethod)) return;
                    // Helpers in the user's own sources (same class or other project
                    // classes) are inlined by TornadoVM; KernelCallGraphAnalyzer already
                    // adds their bodies to the analysis scope, so only library calls count.
                    if (KernelCallGraphAnalyzer.isUserProjectMethod(calledMethod)) return;
                    PsiClass containingClass = calledMethod.getContainingClass();
                    if (containingClass == null) return;
                    String qualifiedName = containingClass.getQualifiedName();
                    // "_Dummy_" is the synthetic holder of the platform's array class,
                    // so array methods such as a.clone() resolve to it
                    if (qualifiedName != null && !qualifiedName.startsWith("java.") &&
                            !qualifiedName.startsWith("uk.ac.manchester.tornado") &&
                            !qualifiedName.startsWith("_Dummy_")) {
                        PsiElement anchor = scope.anchorFor(expression, method, sink.file());
                        if (anchor == null) return;
                        sink.report(anchor, MessageBundle.message("inspection.externalLibrary") + context,
                                ProblemHighlightType.WARNING);
                    }
                }
            });
        }
    }
}
