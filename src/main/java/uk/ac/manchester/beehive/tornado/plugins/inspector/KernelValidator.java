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

package uk.ac.manchester.beehive.tornado.plugins.inspector;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Decides whether a kernel has problems that stop it from running on TornadoVM,
 * using the same checks as the inspections but without the inspection framework,
 * so the result does not depend on whether or when the inspections ran.
 */
public final class KernelValidator {

    // The kernel inspections whose problems hide a kernel from the task panel. This list
    // is kept explicit rather than derived from the plugin.xml levels:
    // ExternalLibraryInspection is registered as WARNING (the severity it reports) but
    // still blocks. Not listed: NonVoidReturnInspection (WARNING; non-void kernels are
    // rejected separately by the task panel), UnanalysableHelperInspection (WEAK WARNING)
    // and StaticTaskGraphInspection (not kernel-scoped).
    private static final List<TornadoKernelInspection> BLOCKING_CHECKS = List.of(
            new DataTypeInspection(),
            new RecursionInspection(),
            new ThrowInspection(),
            new AssertInspection(),
            new SystemCallInspection(),
            new ExternalLibraryInspection());

    private KernelValidator() {
    }

    /**
     * Returns whether any blocking check reports a problem in {@code kernel} or in
     * the helpers it inlines. Must be called with read access.
     *
     * @param kernel a method accepted by {@link TornadoKernels#isKernel}
     */
    public static boolean hasBlockingProblems(@NotNull PsiMethod kernel) {
        PsiFile file = kernel.getContainingFile();
        if (file == null) return false;
        KernelCallGraphAnalyzer.AnalysisScope scope = KernelCallGraphAnalyzer.resolve(kernel);
        FoundSink sink = new FoundSink(file);
        for (TornadoKernelInspection check : BLOCKING_CHECKS) {
            check.checkKernel(kernel, scope, sink);
            if (sink.found) return true;
        }
        return false;
    }

    private static final class FoundSink implements KernelProblemSink {
        private final PsiFile file;
        private boolean found;

        FoundSink(PsiFile file) {
            this.file = file;
        }

        @Override
        public @NotNull PsiFile file() {
            return file;
        }

        @Override
        public void report(@NotNull PsiElement anchor, @NotNull String message, @NotNull ProblemHighlightType type) {
            found = true;
        }
    }
}
