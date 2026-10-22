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
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Reports the call sites in a TornadoVM kernel whose helper method could not be
 * analysed (unresolved call, no source, native or out-of-project method), so the
 * other kernel inspections skipped that helper's body.
 * <p>
 * This is the only inspection that reports these call sites; the other kernel
 * inspections share the same {@link KernelCallGraphAnalyzer} scope and only report
 * their own findings.
 * </p>
 */
public class UnanalysableHelperInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernel, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (Map.Entry<PsiMethodCallExpression, String> entry : scope.getNonAnalyzableCallSites().entrySet()) {
            sink.report(entry.getKey(),
                    MessageBundle.message("inspection.helper.unresolvable") + ": " + entry.getValue(),
                    ProblemHighlightType.WEAK_WARNING);
        }
    }
}
