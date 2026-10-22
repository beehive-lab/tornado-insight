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
import uk.ac.manchester.beehive.tornado.plugins.entity.ProblemMethods;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

/**
 * This inspection tool identifies and highlights unsupported data types
 * within TornadoVM kernels ({@code @Parallel}/{@code @Reduce}, KernelContext and
 * TileContext kernels, see {@link TornadoKernels}).
 *
 * <p>Kernels should use data types that are
 * supported by the TornadoVM. Any usage of unsupported data types will be
 * highlighted as an error in the IDE.</p>
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
        if (!type.equalsToText("int") && !type.equalsToText("boolean") && !type.equalsToText("double")
                && !type.equalsToText("long") && !type.equalsToText("char") && !type.equalsToText("float")
                && !type.equalsToText("byte") && !type.equalsToText("short")
                && !type.getCanonicalText().startsWith("int[]") && !type.getCanonicalText().startsWith("boolean[]")
                && !type.getCanonicalText().startsWith("double[]") && !type.getCanonicalText().startsWith("long[]")
                && !type.getCanonicalText().startsWith("char[]") && !type.getCanonicalText().startsWith("float[]")
                && !type.getCanonicalText().startsWith("byte[]") && !type.getCanonicalText().startsWith("short[]")
                && !type.equalsToText("Int3") && !type.getCanonicalText().startsWith("uk.ac.manchester.tornado.api.")
                && !type.getCanonicalText().startsWith("java.util.concurrent.atomic.AtomicInteger")) {
            PsiMethod parentMethod = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class);
            if (parentMethod != null) {
                ProblemMethods.getInstance().addMethod(sink.file().getProject(), sink.file(), parentMethod);
            }
            sink.report(anchor, MessageBundle.message("inspection.datatype") + context, ProblemHighlightType.ERROR);
        }
    }
}
