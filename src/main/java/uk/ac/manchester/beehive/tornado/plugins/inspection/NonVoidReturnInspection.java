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
 * A custom inspection tool to check for non-void return types of TornadoVM kernels:
 * methods with "Parallel" or "Reduce" annotations, or with a KernelContext or TileContext
 * parameter (see {@link TornadoKernels}).
 * <p>
 * Methods registered as kernel entry points via .task() must return void. However,
 * non-void return types are valid when the method is inlined as a helper, so this
 * inspection registers a WARNING rather than an ERROR.
 * </p>
 */
public class NonVoidReturnInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod method, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiTypes.voidType().equals(returnType)) {
            PsiTypeElement returnTypeElement = method.getReturnTypeElement();
            if (returnTypeElement != null) {
                LocalQuickFix[] fixes = method.getBody() != null
                        ? new LocalQuickFix[]{new MakeVoidFix()}
                        : LocalQuickFix.EMPTY_ARRAY;
                sink.report(returnTypeElement, MessageBundle.message("inspection.nonVoidReturn"),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fixes);
            }
        }
    }
}
