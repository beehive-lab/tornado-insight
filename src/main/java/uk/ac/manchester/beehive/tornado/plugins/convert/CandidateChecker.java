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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.inspection.KernelValidator;
import uk.ac.manchester.beehive.tornado.plugins.kernel.ParallelPlacement;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The static checks of a candidate kernel, before anything is shown to the user: it parses,
 * its names resolve, it keeps the original parameters, it is a {@code void} kernel of the
 * target's kind (Loop Parallel with {@code @Parallel} only on loop indexes, or a Kernel API kernel
 * with a context and a valid grid comment), it uses only what {@link RunnableKernel} allows, and
 * the kernel inspections accept it. Running it is a later step.
 */
final class CandidateChecker {

    private static final Pattern GRID_CHARACTERS = Pattern.compile("[\\w\\s.()+\\-*/%<>,]+");
    private static final Set<IElementType> GRID_OPERATORS = Set.of(JavaTokenType.PLUS, JavaTokenType.MINUS,
            JavaTokenType.ASTERISK, JavaTokenType.DIV, JavaTokenType.PERC, JavaTokenType.LTLT, JavaTokenType.GTGT);

    private CandidateChecker() {
    }

    /**
     * Returns the problems of {@code kernel}, which replaces {@code original} in a copy of its
     * file, as messages for the model; empty if it passes. Must be called with read access.
     */
    static @NotNull List<String> problems(@NotNull PsiMethod original, @NotNull PsiMethod kernel, @NotNull Target target) {
        Set<String> problems = new LinkedHashSet<>();
        for (PsiErrorElement error : PsiTreeUtil.findChildrenOfType(kernel, PsiErrorElement.class)) {
            problems.add("Syntax error: " + error.getErrorDescription() + near(error.getParent().getText()));
        }
        PsiType returnType = kernel.getReturnType();
        if (returnType == null || !PsiTypes.voidType().equals(returnType)) {
            problems.add("The kernel must return `void`; put a result in an extra last parameter.");
        }
        // TornadoVM's task is a method reference with the arguments only, as the harness's is
        if (!kernel.hasModifierProperty(PsiModifier.STATIC)) {
            problems.add("The kernel must be `static`.");
        }
        problems.addAll(parameterProblems(original, kernel, target));
        for (PsiJavaCodeReferenceElement reference : PsiTreeUtil.findChildrenOfType(kernel, PsiJavaCodeReferenceElement.class)) {
            if (reference.getReferenceName() != null && reference.advancedResolve(true).getElement() == null) {
                problems.add("`" + reference.getText() + "` cannot be resolved.");
            }
        }
        if (problems.isEmpty()) {
            problems.addAll(RunnableKernel.problems(kernel));
        }
        if (target.context() == null) {
            if (TornadoKernels.kind(kernel) != TornadoKernels.Kind.PARALLEL_LOOP) {
                problems.add("The kernel has no `@Parallel` loop: put `@Parallel` on the index of a loop whose iterations are independent,"
                        + " or, if no loop's iterations are independent, answer that the method cannot be parallelised.");
            }
            if (!ParallelPlacement.misplaced(kernel).isEmpty()) {
                problems.add("`@Parallel` may only annotate the index variable of a `for` loop.");
            }
        } else {
            problems.addAll(contextProblems(kernel, target));
        }
        if (problems.isEmpty()) {
            // The kernel checks need resolved code to give meaningful messages
            problems.addAll(KernelValidator.blockingProblems(kernel));
        }
        return new ArrayList<>(problems);
    }

    // A kernel with a context: the context first, no @Parallel, a grid over the parameters
    private static List<String> contextProblems(PsiMethod kernel, Target target) {
        List<String> problems = new ArrayList<>();
        PsiParameter[] parameters = kernel.getParameterList().getParameters();
        if (parameters.length == 0 || TornadoKernels.kind(kernel) != target.kind() || !hasContext(kernel, target)) {
            problems.add("The kernel must take a `" + target.contextSimpleName() + "` as its first parameter.");
        }
        for (PsiAnnotation annotation : PsiTreeUtil.findChildrenOfType(kernel, PsiAnnotation.class)) {
            String name = annotation.getQualifiedName();
            if (name != null && (name.equals(TornadoKernels.PARALLEL) || name.equals(TornadoKernels.REDUCE))) {
                problems.add("Do not use `@" + annotation.getNameReferenceElement().getReferenceName() + "` in a "
                        + target.displayName() + " kernel: each thread finds its element from the context.");
                break;
            }
        }
        KernelGrid grid = KernelGrid.parse(kernel.getText());
        if (grid == null) {
            problems.add("Start the method body with a comment that gives the grid, one size per dimension, for example `// grid: x.length`.");
            return problems;
        }
        if (grid.global().isEmpty() || grid.global().size() > 3) {
            problems.add("The grid comment must give one to three sizes, not " + grid.global().size() + ".");
        }
        if (!target.hasLocalWork() && !grid.local().isEmpty()) {
            problems.add("A " + target.displayName() + " kernel has no `// local:` work-group size; remove it.");
        } else if (grid.local().size() > grid.global().size()) {
            problems.add("The `// local:` comment gives more sizes than the grid has dimensions.");
        }
        List<String> sizes = new ArrayList<>(grid.global());
        sizes.addAll(grid.local());
        for (String size : sizes) {
            String problem = sizeProblem(kernel, size);
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    private static boolean hasContext(PsiMethod kernel, Target target) {
        PsiParameter[] parameters = kernel.getParameterList().getParameters();
        return parameters.length > 0 && parameters[0].getType().getCanonicalText().equals(target.context());
    }

    // A grid size must be an int expression over the kernel's parameters. The harness runs it as
    // Java before the kernel, so only a small grammar is accepted: int literals, the parameters,
    // their .length or getSize(), arithmetic and Math.min/max
    private static String sizeProblem(PsiMethod kernel, String size) {
        // No strings, characters, comments or Unicode escapes, which javac and the PSI could read apart
        if (!GRID_CHARACTERS.matcher(size).matches() || size.contains("//") || size.contains("/*")) {
            return "`" + size + "` in the grid comment may only use the kernel's parameters, int literals and arithmetic.";
        }
        PsiExpression expression;
        try {
            expression = JavaPsiFacade.getElementFactory(kernel.getProject()).createExpressionFromText(size, kernel.getBody());
        } catch (IncorrectOperationException e) {
            return "`" + size + "` in the grid comment is not a Java expression.";
        }
        PsiElement disallowed = disallowed(expression, kernel);
        if (disallowed != null) {
            return "The grid may only use the kernel's parameters, their `.length` or `getSize()`, int literals, "
                    + "arithmetic and `Math.min`/`Math.max`: `" + disallowed.getText() + "` in `" + size + "` is not allowed.";
        }
        PsiType type = expression.getType();
        if (type == null || !TypeConversionUtil.isIntegralNumberType(type) || PsiTypes.longType().equals(type)) {
            return "The grid size `" + size + "` must be an `int`.";
        }
        return null;
    }

    // The first part of a grid size outside its grammar, or null
    private static @Nullable PsiElement disallowed(@Nullable PsiExpression expression, PsiMethod kernel) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof PsiLiteralExpression) {
            return null;
        }
        if (expression instanceof PsiParenthesizedExpression parenthesized) {
            return parenthesized.getExpression() == null ? expression : disallowed(parenthesized.getExpression(), kernel);
        }
        if (expression instanceof PsiPrefixExpression prefix) {
            IElementType sign = prefix.getOperationTokenType();
            return sign == JavaTokenType.MINUS || sign == JavaTokenType.PLUS ? disallowed(prefix.getOperand(), kernel) : expression;
        }
        if (expression instanceof PsiPolyadicExpression polyadic) {
            if (!GRID_OPERATORS.contains(polyadic.getOperationTokenType())) {
                return expression;
            }
            for (PsiExpression operand : polyadic.getOperands()) {
                PsiElement found = disallowed(operand, kernel);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (expression instanceof PsiTypeCastExpression cast) {
            return cast.getCastType() != null && cast.getCastType().getType() instanceof PsiPrimitiveType
                    ? disallowed(cast.getOperand(), kernel) : expression;
        }
        if (expression instanceof PsiReferenceExpression reference) {
            PsiExpression qualifier = reference.getQualifierExpression();
            if (qualifier == null) {
                return isParameter(reference, kernel) ? null : expression;
            }
            return "length".equals(reference.getReferenceName()) && isParameter(qualifier, kernel)
                    && qualifier.getType() instanceof PsiArrayType ? null : expression;
        }
        if (expression instanceof PsiMethodCallExpression call) {
            PsiReferenceExpression method = call.getMethodExpression();
            PsiExpression qualifier = method.getQualifierExpression();
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if ("getSize".equals(method.getReferenceName()) && arguments.length == 0 && isParameter(qualifier, kernel)) {
                return null;
            }
            if (("min".equals(method.getReferenceName()) || "max".equals(method.getReferenceName()))
                    && qualifier instanceof PsiReferenceExpression owner && owner.getQualifierExpression() == null
                    && owner.resolve() instanceof PsiClass psiClass && "java.lang.Math".equals(psiClass.getQualifiedName())) {
                for (PsiExpression argument : arguments) {
                    PsiElement found = disallowed(argument, kernel);
                    if (found != null) {
                        return found;
                    }
                }
                return null;
            }
        }
        return expression;
    }

    // An unqualified name of one of the kernel's parameters
    private static boolean isParameter(@Nullable PsiExpression expression, PsiMethod kernel) {
        return expression instanceof PsiReferenceExpression reference && reference.getQualifierExpression() == null
                && reference.resolve() instanceof PsiParameter parameter && parameter.getDeclarationScope() == kernel;
    }

    private static List<String> parameterProblems(PsiMethod original, PsiMethod kernel, Target target) {
        PsiParameter[] expected = original.getParameterList().getParameters();
        PsiParameter[] actual = kernel.getParameterList().getParameters();
        if (target.context() != null && hasContext(kernel, target)) {
            actual = Arrays.copyOfRange(actual, 1, actual.length);
        }
        boolean returnsValue = original.getReturnType() != null && !PsiTypes.voidType().equals(original.getReturnType());
        int allowed = expected.length + (returnsValue ? 1 : 0);
        List<String> problems = new ArrayList<>();
        if (actual.length < expected.length || actual.length > allowed) {
            problems.add("Keep the original parameters (" + types(expected) + ")"
                    + (returnsValue ? ", plus one last parameter for the result" : "") + "; the kernel has (" + types(actual) + ").");
            return problems;
        }
        for (int i = 0; i < expected.length; i++) {
            String want = expected[i].getType().getCanonicalText();
            String got = actual[i].getType().getCanonicalText();
            if (!want.equals(got)) {
                problems.add("Parameter " + (i + 1) + " must keep the type `" + want + "`, not `" + got + "`.");
            }
        }
        return problems;
    }

    private static String types(PsiParameter[] parameters) {
        List<String> types = new ArrayList<>();
        for (PsiParameter parameter : parameters) {
            types.add(parameter.getType().getPresentableText());
        }
        return String.join(", ", types);
    }

    private static String near(String code) {
        String line = code.strip().replaceAll("\\s+", " ");
        return line.isEmpty() ? "" : " near `" + (line.length() > 60 ? line.substring(0, 60) + "…" : line) + "`";
    }
}
