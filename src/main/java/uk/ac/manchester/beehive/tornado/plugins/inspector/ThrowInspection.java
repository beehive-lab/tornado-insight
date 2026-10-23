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
 * A custom inspection tool to check for thrown exceptions within TornadoVM kernels
 * (see {@link TornadoKernels}) as TornadoVM does not support for traps/exceptions.
 * <p>
 * This inspection tool ensures that:
 * - No exceptions are thrown within the method body.
 * - The method body does not contain any try/catch blocks.
 * - No exceptions are declared to be thrown in the method's signature.
 * </p>
 * <p>
 * The inspection follows method calls across class boundaries, analyzing all helper methods
 * that TornadoVM would inline at runtime.
 * </p>
 */
public class ThrowInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (PsiMethod method : scope.getAnalyzableMethods()) {
            checkThrow(method, kernelMethod, scope, sink);
        }
    }

    private void checkThrow(PsiMethod method, PsiMethod kernelMethod,
                            KernelCallGraphAnalyzer.AnalysisScope scope, KernelProblemSink sink) {
        String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitThrowStatement(PsiThrowStatement statement) {
                super.visitThrowStatement(statement);
                PsiElement anchor = scope.anchorFor(statement, method, sink.file());
                if (anchor == null) return;
                sink.report(anchor, MessageBundle.message("inspection.traps.throw") + context, ProblemHighlightType.ERROR);
            }

            @Override
            public void visitTryStatement(PsiTryStatement statement) {
                super.visitTryStatement(statement);
                PsiElement anchor = scope.anchorFor(statement, method, sink.file());
                if (anchor == null) return;
                sink.report(anchor, MessageBundle.message("inspection.traps.tryCatch") + context, ProblemHighlightType.ERROR);
            }
        });

        // Checking the method signature for thrown exceptions
        for (PsiClassType exception : method.getThrowsList().getReferencedTypes()) {
            PsiElement anchor = scope.anchorFor(method.getThrowsList(), method, sink.file());
            if (anchor == null) continue;
            sink.report(anchor,
                    MessageBundle.message("inspection.traps.throws") + "\n" + exception.getCanonicalText() + context,
                    ProblemHighlightType.ERROR);
        }
    }
}
