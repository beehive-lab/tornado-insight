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

package uk.ac.manchester.beehive.tornado.plugins.kernel;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Receives the problems that a {@link uk.ac.manchester.beehive.tornado.plugins.inspection.TornadoKernelInspection} finds in a kernel.
 * The inspection itself forwards them to its {@code ProblemsHolder}; other callers
 * can collect them without running the inspection framework.
 */
public interface KernelProblemSink {

    /**
     * The file being checked. Problems must be anchored on elements of this file;
     * see {@link KernelCallGraphAnalyzer.AnalysisScope#anchorFor}.
     */
    @NotNull PsiFile file();

    /**
     * Reports a problem on {@code anchor}, an element of {@link #file()}.
     */
    void report(@NotNull PsiElement anchor, @NotNull String message, @NotNull ProblemHighlightType type);
}
