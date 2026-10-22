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
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * The `RecursionInspection` class checks for recursive calls within TornadoVM
 * kernels (see {@link TornadoKernels}).
 *
 * <p>Kernels are expected to avoid recursive
 * calls. Any detected recursive call will be highlighted as an error in the IDE.</p>
 *
 * <p>The inspection follows method calls across class boundaries, analyzing all
 * helper methods that TornadoVM would inline at runtime.</p>
 */
public class RecursionInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (PsiMethod method : scope.getAnalyzableMethods()) {
            checkRecursion(method, kernelMethod, scope, sink);
        }
    }

    private void checkRecursion(PsiMethod method, PsiMethod kernelMethod,
                                KernelCallGraphAnalyzer.AnalysisScope scope, KernelProblemSink sink) {
        String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitCallExpression(PsiCallExpression callExpression) {
                super.visitCallExpression(callExpression);
                PsiMethod calledMethod = callExpression.resolveMethod();
                Set<PsiMethod> visited = new HashSet<>();
                if (isRecursive(calledMethod, visited)) {
                    if (calledMethod == null) return;
                    // Prefer highlighting the recursive method declaration;
                    // fall back to the call site when it is in another file.
                    PsiElement anchor = scope.anchorFor(calledMethod, method, sink.file());
                    if (anchor == null) {
                        anchor = scope.anchorFor(callExpression, method, sink.file());
                    }
                    if (anchor == null) return;
                    sink.report(anchor, MessageBundle.message("inspection.recursion") + context, ProblemHighlightType.ERROR);
                }
            }
        });
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
