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

package uk.ac.manchester.beehive.tornado.plugins.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.*;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelProblemSink;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

/**
 * This inspection tool identifies and highlights the usage of the 'assert' keyword
 * within TornadoVM kernels (see {@link TornadoKernels}).
 *
 * <p>Kernels are expected not to use assertions,
 * and any such use will be highlighted as an error in the IDE.</p>
 *
 * <p>The inspection follows method calls across class boundaries, analyzing all
 * helper methods that TornadoVM would inline at runtime.</p>
 */
public class AssertInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (PsiMethod method : scope.getAnalyzableMethods()) {
            String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitAssertStatement(PsiAssertStatement statement) {
                    super.visitAssertStatement(statement);
                    PsiElement anchor = scope.anchorFor(statement, method, sink.file());
                    if (anchor == null) return;
                    // An assert in another file's helper is reported on the kernel's call
                    // into it; removing that call would be wrong, so it gets no fix
                    LocalQuickFix[] fixes = anchor == statement
                            ? new LocalQuickFix[]{new RemoveStatementFix("quickfix.removeAssert")}
                            : LocalQuickFix.EMPTY_ARRAY;
                    sink.report(anchor, MessageBundle.message("inspection.assert") + context, ProblemHighlightType.ERROR, fixes);
                }
            });
        }
    }
}
