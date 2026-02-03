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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.entity.RestrictedClasses;

import java.util.*;

/**
 * Resolves cross-class method calls from kernel methods and determines
 * which helper methods should be treated as inlined for analysis purposes.
 * <p>
 * The analyzer builds a transitive call graph starting from a kernel method,
 * following method calls across class boundaries. Each resolved callee is
 * checked against eligibility rules. Eligible methods have their bodies
 * included in the analysis scope; ineligible methods are reported as
 * non-analyzable.
 * </p>
 * <p>
 * Results are cached per kernel method and invalidated automatically when
 * any PSI modification occurs, ensuring consistency without redundant work
 * when multiple inspections analyze the same kernel.
 * </p>
 */
public final class KernelCallGraphAnalyzer {

    private static final int MAX_INLINE_DEPTH = 10;

    /**
     * Result of analyzing a kernel's call graph.
     */
    public static final class AnalysisScope {
        private final List<PsiMethod> analyzableMethods;
        private final Map<PsiMethodCallExpression, String> nonAnalyzableCallSites;

        AnalysisScope(List<PsiMethod> analyzableMethods,
                      Map<PsiMethodCallExpression, String> nonAnalyzableCallSites) {
            this.analyzableMethods = Collections.unmodifiableList(analyzableMethods);
            this.nonAnalyzableCallSites = Collections.unmodifiableMap(nonAnalyzableCallSites);
        }

        /**
         * All methods whose bodies should be analyzed (kernel + inlineable helpers).
         */
        public List<PsiMethod> getAnalyzableMethods() {
            return analyzableMethods;
        }

        /**
         * Methods that were called but could not be inlined, mapped to a
         * human-readable reason string suitable for IDE diagnostics.
         */
        public Map<PsiMethodCallExpression, String> getNonAnalyzableCallSites() {
            return nonAnalyzableCallSites;
        }
    }

    private KernelCallGraphAnalyzer() {
    }

    /**
     * Build the full analysis scope for a kernel method by resolving
     * all transitive helper method calls. Results are cached per kernel
     * method and invalidated on any PSI modification.
     *
     * @param kernelMethod the {@code @Parallel}/{@code @Reduce}/KernelContext method
     * @return the analysis scope containing all inlineable method bodies
     *         and diagnostics for non-analyzable call sites
     */
    public static AnalysisScope resolve(PsiMethod kernelMethod) {
        return CachedValuesManager.getCachedValue(kernelMethod, () -> {
            AnalysisScope scope = doResolve(kernelMethod);
            return CachedValueProvider.Result.create(
                    scope, PsiModificationTracker.MODIFICATION_COUNT);
        });
    }

    private static AnalysisScope doResolve(PsiMethod kernelMethod) {
        List<PsiMethod> analyzable = new ArrayList<>();
        Map<PsiMethodCallExpression, String> nonAnalyzable = new LinkedHashMap<>();
        Set<PsiMethod> visited = new HashSet<>();

        analyzable.add(kernelMethod);
        visited.add(kernelMethod);
        collectTransitiveCallees(kernelMethod, visited, analyzable, nonAnalyzable, 0);

        return new AnalysisScope(analyzable, nonAnalyzable);
    }

    private static void collectTransitiveCallees(
            PsiMethod method,
            Set<PsiMethod> visited,
            List<PsiMethod> analyzable,
            Map<PsiMethodCallExpression, String> nonAnalyzable,
            int depth) {

        if (depth >= MAX_INLINE_DEPTH) return;

        PsiCodeBlock body = method.getBody();
        if (body == null) return;

        Collection<PsiMethodCallExpression> calls =
                PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);

        for (PsiMethodCallExpression callExpr : calls) {
            PsiMethod resolved = callExpr.resolveMethod();
            if (resolved == null) {
                nonAnalyzable.put(callExpr,
                        "Cannot resolve method '" + callExpr.getText() + "'");
                continue;
            }

            if (visited.contains(resolved)) continue;

            String ineligibilityReason = checkEligibility(resolved);
            if (ineligibilityReason != null) {
                if (isUserProjectMethod(resolved)) {
                    nonAnalyzable.put(callExpr, ineligibilityReason);
                }
                continue;
            }

            visited.add(resolved);
            analyzable.add(resolved);
            collectTransitiveCallees(resolved, visited, analyzable, nonAnalyzable, depth + 1);
        }
    }

    /**
     * Returns {@code null} if the method is eligible for kernel inline analysis,
     * or a human-readable reason string if it is not.
     */
    static String checkEligibility(PsiMethod method) {
        if (method.getBody() == null) {
            return "Method '" + method.getName() + "' has no source body available for analysis";
        }

        if (method.hasModifierProperty(PsiModifier.NATIVE)) {
            return "Method '" + method.getName() + "' is native";
        }

        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            String qName = containingClass.getQualifiedName();
            if (qName != null) {
                // Restricted system classes — not an error, just not analyzed
                if (RestrictedClasses.isRestrictedClass(qName)) {
                    return null;
                }
                // TornadoVM API intrinsics — handled by runtime
                if (qName.startsWith("uk.ac.manchester.tornado.api.")) {
                    return null;
                }
            }
        }

        if (!isUserProjectMethod(method)) {
            return null;
        }

        return null;
    }

    /**
     * Checks whether a method is defined within the project's source roots
     * (as opposed to a library dependency).
     */
    public static boolean isUserProjectMethod(PsiMethod method) {
        PsiFile file = method.getContainingFile();
        if (file == null) return false;
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null) return false;

        Project project = method.getProject();
        return ProjectRootManager.getInstance(project)
                .getFileIndex()
                .isInSourceContent(vFile);
    }

    /**
     * Checks whether the given annotation marks a kernel method.
     *
     * @param annotation the annotation to check
     * @return true if it is {@code @Parallel} or {@code @Reduce}
     */
    public static boolean isKernelAnnotation(PsiAnnotation annotation) {
        String qName = annotation.getQualifiedName();
        return qName != null
                && (qName.endsWith("Parallel") || qName.endsWith("Reduce"));
    }

    /**
     * Formats a context suffix for diagnostic messages when a violation is found
     * inside a helper method rather than the kernel method itself.
     *
     * @param currentMethod  the method currently being analyzed
     * @param kernelMethod   the top-level kernel method
     * @return a context string, or empty string if they are the same method
     */
    public static String helperContext(PsiMethod currentMethod, PsiMethod kernelMethod) {
        if (currentMethod.equals(kernelMethod)) {
            return "";
        }
        PsiClass cls = currentMethod.getContainingClass();
        String className = cls != null ? cls.getName() : "?";
        return " (in helper '" + className + "." + currentMethod.getName() + "' inlined into kernel)";
    }
}
