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
import uk.ac.manchester.beehive.tornado.plugins.kernel.RestrictedClasses;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelProblemSink;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

/**
 * A custom inspection tool to check for invocations of potentially problematic system and external methods
 * within TornadoVM kernels (see {@link TornadoKernels}).
 * <p>
 * This inspection tool searches for:
 * - Calls to native methods
 * - Calls to methods from various Java system and utility classes that may lead to unexpected behavior
 *   or resource usage in parallel or reduced contexts.
 * </p>
 * <p>
 * The inspection follows method calls across class boundaries, analyzing all helper methods
 * that TornadoVM would inline at runtime.
 * </p>
 */
public class SystemCallInspection extends TornadoKernelInspection {

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (PsiMethod method : scope.getAnalyzableMethods()) {
            String context = KernelCallGraphAnalyzer.helperContext(method, kernelMethod);
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    super.visitMethodCallExpression(expression);
                    PsiMethod calledMethod = expression.resolveMethod();
                    PsiElement anchor = scope.anchorFor(expression, method, sink.file());
                    if (anchor == null) return;
                    // Removable only when the call is a statement of its own (its value is
                    // unused) and is reported where it is, not on the call into another
                    // file's helper
                    LocalQuickFix[] fixes = anchor == expression && RemoveStatementFix.statementOf(expression) != null
                            ? new LocalQuickFix[]{new RemoveStatementFix("quickfix.removeCall")}
                            : LocalQuickFix.EMPTY_ARRAY;
                    if (calledMethod != null && calledMethod.hasModifierProperty(PsiModifier.NATIVE)) {
                        sink.report(anchor, MessageBundle.message("inspection.nativeCall") + context, ProblemHighlightType.ERROR,
                                fixes);
                        // A native method of a restricted class is reported twice; offer the fix once
                        fixes = LocalQuickFix.EMPTY_ARRAY;
                    }
                    if (calledMethod == null) return;
                    // Anonymous and local classes have no qualified name, and are never restricted
                    PsiClass containingClass = calledMethod.getContainingClass();
                    String className = containingClass == null ? null : containingClass.getQualifiedName();
                    if (className != null && RestrictedClasses.isRestrictedClass(className)) {
                        sink.report(anchor, MessageBundle.message("inspection.external") + context, ProblemHighlightType.ERROR,
                                fixes);
                    }
                }
            });
        }
    }
}
