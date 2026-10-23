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

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.*;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelProblemSink;
import uk.ac.manchester.beehive.tornado.plugins.kernel.SupportedKernelTypes;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;
import org.jetbrains.annotations.NotNull;

/**
 * This inspection tool identifies and highlights unsupported data types
 * within TornadoVM kernels ({@code @Parallel}/{@code @Reduce}, KernelContext and
 * TileContext kernels, see {@link TornadoKernels}).
 *
 * <p>Kernels should use data types that are
 * supported by the TornadoVM. Any usage of unsupported data types will be
 * highlighted as an error in the IDE. The supported types are listed in a
 * resource file, see {@link SupportedKernelTypes}.</p>
 *
 * <p>The inspection follows method calls across class boundaries, analyzing all
 * helper methods that TornadoVM would inline at runtime.</p>
 */
public class DataTypeInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (PsiMethod method : scope.getAnalyzableMethods()) {
            String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitLocalVariable(PsiLocalVariable variable) {
                    checkVariable(variable.getType(), context, scope.anchorFor(variable, method, sink.file()), sink);
                }

                @Override
                public void visitField(PsiField field) {
                    checkVariable(field.getType(), context, scope.anchorFor(field, method, sink.file()), sink);
                }

                @Override
                public void visitParameter(PsiParameter parameter) {
                    checkVariable(parameter.getType(), context, scope.anchorFor(parameter, method, sink.file()), sink);
                }
            });
        }
    }

    /**
     * Checks the type of a variable. If the type is unsupported, a problem is
     * reported on {@code anchor}.
     *
     * @param type    The type of the variable to be checked.
     * @param context Helper context string for diagnostics.
     * @param anchor  In-file element to register the problem on; null when
     *                the finding cannot be anchored in the inspected file.
     * @param sink    Receives the problem.
     */
    private static void checkVariable(PsiType type, String context, PsiElement anchor, KernelProblemSink sink) {
        if (anchor == null) return;
        // A class that does not resolve (e.g. the TornadoVM API is not on the module's
        // classpath) cannot be classified; the editor already reports it as an error
        if (type.getDeepComponentType() instanceof PsiClassType classType && classType.resolve() == null) return;
        if (!SupportedKernelTypes.get().isSupported(type)) {
            sink.report(anchor, MessageBundle.message("inspection.datatype") + context, ProblemHighlightType.ERROR);
        }
    }
}
