/*
 * Copyright (c) 2023, 2025, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Analyses a kernel method's PSI tree to detect which {@code int} parameters
 * flow into {@code KernelContext.allocate*LocalArray(size)} calls.
 * <p>
 * Parameters that feed into local-memory allocation sizes need to be
 * constrained to small, safe values during dynamic inspection code generation,
 * preventing {@code CL_INVALID_KERNEL} errors caused by oversized local memory.
 */
public final class LocalMemoryParameterAnalyzer {

    private static final Pattern ALLOCATE_LOCAL_ARRAY = Pattern.compile(
            "allocate(Float|Int|Double|Long|Byte|HalfFloat|Short)LocalArray");

    private LocalMemoryParameterAnalyzer() {
    }

    /**
     * Returns the names of kernel method parameters whose values flow into a
     * {@code KernelContext.allocate*LocalArray(size)} call.
     *
     * @param method the kernel method to analyse
     * @return an unmodifiable set of parameter names (may be empty, never null)
     */
    public static @NotNull Set<String> findLocalMemoryParameters(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return Collections.emptySet();
        }

        Set<PsiParameter> kernelParams = new HashSet<>();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            kernelParams.add(p);
        }
        if (kernelParams.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> result = new HashSet<>();

        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
            if (!isAllocateLocalArrayCall(call)) {
                continue;
            }

            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (args.length == 0) {
                continue;
            }

            // The first argument is the size expression
            collectParameterReferences(args[0], kernelParams, body, result);
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Checks whether a method call matches the pattern
     * {@code <KernelContext-typed-expr>.allocate<Type>LocalArray(...)}.
     */
    private static boolean isAllocateLocalArrayCall(@NotNull PsiMethodCallExpression call) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null || !ALLOCATE_LOCAL_ARRAY.matcher(methodName).matches()) {
            return false;
        }

        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return false;
        }

        PsiType qualifierType = qualifier.getType();
        if (qualifierType == null) {
            return false;
        }

        String typeName = qualifierType.getCanonicalText();
        return typeName != null && typeName.endsWith("KernelContext");
    }

    /**
     * Recursively walks an expression tree to find all references to kernel
     * method parameters. Handles direct references, arithmetic, parenthesized
     * expressions, and single-level local variable indirection.
     */
    private static void collectParameterReferences(
            @NotNull PsiExpression expr,
            @NotNull Set<PsiParameter> kernelParams,
            @NotNull PsiCodeBlock methodBody,
            @NotNull Set<String> result) {

        if (expr instanceof PsiReferenceExpression ref) {
            PsiElement resolved = ref.resolve();

            if (resolved instanceof PsiParameter param && kernelParams.contains(param)) {
                result.add(param.getName());
                return;
            }

            // Local variable indirection: int x = param; allocate...(x)
            if (resolved instanceof PsiLocalVariable localVar) {
                PsiExpression initializer = localVar.getInitializer();
                if (initializer != null) {
                    collectParameterReferences(initializer, kernelParams, methodBody, result);
                }
                // Also check for assignments to this variable in the method body
                collectAssignmentSources(localVar, kernelParams, methodBody, result);
                return;
            }
        }

        if (expr instanceof PsiParenthesizedExpression paren) {
            PsiExpression inner = paren.getExpression();
            if (inner != null) {
                collectParameterReferences(inner, kernelParams, methodBody, result);
            }
            return;
        }

        if (expr instanceof PsiBinaryExpression binary) {
            PsiExpression left = binary.getLOperand();
            PsiExpression right = binary.getROperand();
            collectParameterReferences(left, kernelParams, methodBody, result);
            if (right != null) {
                collectParameterReferences(right, kernelParams, methodBody, result);
            }
            return;
        }

        if (expr instanceof PsiPrefixExpression prefix) {
            PsiExpression operand = prefix.getOperand();
            if (operand != null) {
                collectParameterReferences(operand, kernelParams, methodBody, result);
            }
            return;
        }

        if (expr instanceof PsiTypeCastExpression cast) {
            PsiExpression operand = cast.getOperand();
            if (operand != null) {
                collectParameterReferences(operand, kernelParams, methodBody, result);
            }
            return;
        }

        if (expr instanceof PsiConditionalExpression cond) {
            PsiExpression then = cond.getThenExpression();
            PsiExpression else_ = cond.getElseExpression();
            if (then != null) {
                collectParameterReferences(then, kernelParams, methodBody, result);
            }
            if (else_ != null) {
                collectParameterReferences(else_, kernelParams, methodBody, result);
            }
        }
    }

    /**
     * Looks for assignments to a local variable within the method body and
     * traces the assigned values back to kernel parameters.
     */
    private static void collectAssignmentSources(
            @NotNull PsiLocalVariable localVar,
            @NotNull Set<PsiParameter> kernelParams,
            @NotNull PsiCodeBlock methodBody,
            @NotNull Set<String> result) {

        for (PsiAssignmentExpression assignment :
                PsiTreeUtil.findChildrenOfType(methodBody, PsiAssignmentExpression.class)) {
            PsiExpression lhs = assignment.getLExpression();
            if (lhs instanceof PsiReferenceExpression ref && ref.resolve() == localVar) {
                PsiExpression rhs = assignment.getRExpression();
                if (rhs != null) {
                    collectParameterReferences(rhs, kernelParams, methodBody, result);
                }
            }
        }
    }
}
