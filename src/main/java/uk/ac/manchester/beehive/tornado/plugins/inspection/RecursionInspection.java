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
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelCallGraphAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.kernel.KernelProblemSink;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * The `RecursionInspection` class checks for recursive calls within TornadoVM
 * kernels (see {@link TornadoKernels}).
 *
 * <p>Kernels are expected to avoid recursive
 * calls. Any detected recursive call will be highlighted as an error in the IDE.</p>
 *
 * <p>The inspection works on the kernel's analysis scope (the kernel and the helper
 * methods TornadoVM would inline, see {@link KernelCallGraphAnalyzer}): it builds the
 * call graph between those methods once, finds its cycles as strongly connected
 * components, and reports every cycle once, on the call that closes it.</p>
 */
public class RecursionInspection extends TornadoKernelInspection {

    private static final Key<CachedValue<List<RecursiveCall>>> RECURSIVE_CALLS =
            Key.create("tornado.kernel.recursiveCalls");

    /**
     * A call that closes a cycle of the kernel's call graph.
     *
     * @param call   the call expression
     * @param caller the analysable method whose body contains the call
     */
    record RecursiveCall(@NotNull PsiMethodCallExpression call, @NotNull PsiMethod caller) {
    }

    @Override
    protected void checkKernel(@NotNull PsiMethod kernelMethod, @NotNull KernelCallGraphAnalyzer.AnalysisScope scope,
                               @NotNull KernelProblemSink sink) {
        for (RecursiveCall recursive : recursiveCalls(kernelMethod, scope)) {
            PsiElement anchor = scope.anchorFor(recursive.call(), recursive.caller(), sink.file());
            if (anchor == null) continue;
            String context = KernelCallGraphAnalyzer.helperContext(recursive.caller(), kernelMethod);
            sink.report(anchor, MessageBundle.message("inspection.recursion") + context, ProblemHighlightType.ERROR);
        }
    }

    private static List<RecursiveCall> recursiveCalls(PsiMethod kernelMethod,
                                                      KernelCallGraphAnalyzer.AnalysisScope scope) {
        return CachedValuesManager.getCachedValue(kernelMethod, RECURSIVE_CALLS, () ->
                CachedValueProvider.Result.create(findRecursiveCalls(scope.getAnalyzableMethods()),
                        PsiModificationTracker.MODIFICATION_COUNT));
    }

    /**
     * Returns one call per cycle of the call graph between {@code methods}, in the
     * order of {@code methods}. A cycle is a strongly connected component with more
     * than one method, or a method that calls itself. Its reported call is the first
     * call, in the component's methods in {@code methods} order, to the component's
     * first method.
     */
    static List<RecursiveCall> findRecursiveCalls(List<PsiMethod> methods) {
        Map<PsiMethod, Integer> index = new HashMap<>();
        for (PsiMethod method : methods) {
            index.putIfAbsent(method, index.size());
        }
        int n = index.size();
        List<PsiMethod> nodes = new ArrayList<>(index.keySet());
        nodes.sort(Comparator.comparing(index::get));

        // Call edges between analysable methods, with the call expressions behind them
        List<List<Integer>> successors = new ArrayList<>(n);
        List<List<PsiMethodCallExpression>> calls = new ArrayList<>(n);
        List<List<Integer>> callTargets = new ArrayList<>(n);
        for (PsiMethod method : nodes) {
            List<Integer> succ = new ArrayList<>();
            List<PsiMethodCallExpression> callList = new ArrayList<>();
            List<Integer> targetList = new ArrayList<>();
            PsiCodeBlock body = method.getBody();
            if (body != null) {
                for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
                    Integer target = index.get(call.resolveMethod());
                    if (target == null) continue;
                    callList.add(call);
                    targetList.add(target);
                    if (!succ.contains(target)) succ.add(target);
                }
            }
            successors.add(succ);
            calls.add(callList);
            callTargets.add(targetList);
        }

        int[] component = stronglyConnectedComponents(successors);

        List<RecursiveCall> result = new ArrayList<>();
        Set<Integer> reportedComponents = new HashSet<>();
        for (int entry = 0; entry < n; entry++) {
            int comp = component[entry];
            if (reportedComponents.contains(comp)) continue;
            // The first method (in scope order) of each component is its entry
            reportedComponents.add(comp);
            RecursiveCall closing = null;
            for (int caller = entry; caller < n && closing == null; caller++) {
                if (component[caller] != comp) continue;
                List<Integer> targets = callTargets.get(caller);
                for (int k = 0; k < targets.size(); k++) {
                    if (targets.get(k) == entry) {
                        closing = new RecursiveCall(calls.get(caller).get(k), nodes.get(caller));
                        break;
                    }
                }
            }
            // A single-method component without a self-call is not a cycle
            if (closing != null) {
                result.add(closing);
            }
        }
        return result;
    }

    /**
     * Tarjan's algorithm, iterative so deep call chains cannot overflow the stack.
     *
     * @param successors the successor lists of nodes {@code 0..n-1}
     * @return the component id of every node
     */
    static int[] stronglyConnectedComponents(List<List<Integer>> successors) {
        int n = successors.size();
        int[] order = new int[n];
        int[] low = new int[n];
        int[] component = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(order, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        int counter = 0;
        int components = 0;

        // Work stack of (node, next successor position)
        int[] workNode = new int[n];
        int[] workPos = new int[n];
        for (int root = 0; root < n; root++) {
            if (order[root] != -1) continue;
            int depth = 0;
            workNode[0] = root;
            workPos[0] = 0;
            order[root] = low[root] = counter++;
            stack.push(root);
            onStack[root] = true;
            while (depth >= 0) {
                int v = workNode[depth];
                List<Integer> succ = successors.get(v);
                if (workPos[depth] < succ.size()) {
                    int w = succ.get(workPos[depth]++);
                    if (order[w] == -1) {
                        order[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        depth++;
                        workNode[depth] = w;
                        workPos[depth] = 0;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], order[w]);
                    }
                    continue;
                }
                if (low[v] == order[v]) {
                    int w;
                    do {
                        w = stack.pop();
                        onStack[w] = false;
                        component[w] = components;
                    } while (w != v);
                    components++;
                }
                depth--;
                if (depth >= 0) {
                    int parent = workNode[depth];
                    low[parent] = Math.min(low[parent], low[v]);
                }
            }
        }
        return component;
    }
}
