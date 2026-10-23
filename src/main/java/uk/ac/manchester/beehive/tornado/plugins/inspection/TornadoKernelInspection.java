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

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelProblemSink;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.HashSet;
import java.util.Set;

/**
 * Base class of the inspections that check TornadoVM kernels.
 * <p>
 * The visitor calls {@link #checkKernel} exactly once for every method that
 * {@link TornadoKernels#isKernel} accepts, with the kernel's cached call-graph
 * scope, so a kernel with several {@code @Parallel} loops is analysed once.
 * A problem reported twice on the same element with the same message (e.g. in
 * a helper shared by two kernels of the file) is registered once.
 * </p>
 */
public abstract class TornadoKernelInspection extends AbstractBaseJavaLocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        KernelProblemSink sink = new HolderSink(holder);
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);
                if (!TornadoKernels.isKernel(method)) return;
                checkKernel(method, KernelCallGraphAnalyzer.resolve(method), sink);
            }
        };
    }

    /**
     * Checks one kernel and reports the problems found in it, or in the helpers of
     * {@code scope}, to {@code sink}.
     *
     * @param kernel the kernel method
     * @param scope  the kernel's analysis scope (the kernel and its inlined helpers)
     * @param sink   receives the problems
     */
    protected abstract void checkKernel(@NotNull PsiMethod kernel, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                                        @NotNull KernelProblemSink sink);

    private static final class HolderSink implements KernelProblemSink {
        private record Reported(PsiElement anchor, String message) {
        }

        private final ProblemsHolder holder;
        private final Set<Reported> reported = new HashSet<>();

        HolderSink(ProblemsHolder holder) {
            this.holder = holder;
        }

        @Override
        public @NotNull PsiFile file() {
            return holder.getFile();
        }

        @Override
        public void report(@NotNull PsiElement anchor, @NotNull String message, @NotNull ProblemHighlightType type) {
            if (reported.add(new Reported(anchor, message))) {
                holder.registerProblem(anchor, message, type);
            }
        }
    }
}
