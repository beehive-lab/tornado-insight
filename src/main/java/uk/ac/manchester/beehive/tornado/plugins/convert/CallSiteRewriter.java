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

import com.intellij.codeInsight.ExceptionUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiArrayAccessExpression;
import com.intellij.psi.PsiArrayInitializerExpression;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiCatchSection;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiDeclarationStatement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiUnaryExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rewrites the calls to a converted method in its own file so that they launch its kernel with
 * TornadoVM. The first call builds a {@code TaskGraph} and a {@code TornadoExecutionPlan}, in a
 * try-with-resources block, and executes it; the later calls with the same arguments only replay
 * it with {@code execute()}. A call in a loop whose arguments do not change in it gets its plan
 * built before the loop.
 * <p>
 * The TaskGraph transfers to the device the arrays the kernel reads, once
 * ({@code FIRST_EXECUTION}) unless the code changes them between executions
 * ({@code EVERY_EXECUTION}), and back to the host the arrays it writes, after each execution.
 * A Kernel API or Tile API kernel is launched on its grid with a {@code GridScheduler}.
 * Only the calls change, and the statements the TaskGraph and the plan need are added around
 * them; a call that cannot be rewritten safely is left as it is, with the reason.
 * </p>
 * Runs on the file the preview shows (a copy) and on the file itself when the user accepts, so
 * both give the same text: it needs write access unless the file is a non-physical copy.
 */
final class CallSiteRewriter {

    static final String API = TornadoKernels.TORNADO_API;
    static final String EXCEPTION = API + "exceptions.TornadoExecutionPlanException";
    private static final String TASK = "t0";

    private CallSiteRewriter() {
    }

    /**
     * A call that can be rewritten.
     *
     * @param owner the method, lambda or initializer whose body it is in
     * @param key   the same for the calls of {@code owner} with the same arguments
     */
    private record Call(PsiMethodCallExpression call, PsiExpressionStatement statement, PsiElement owner, String key) {
    }

    /**
     * Calls that share a TaskGraph: it is built before {@code first}, and the statements from
     * {@code first} to {@code last} of {@code block} move into the plan's try block.
     *
     * @param replays whether the plan may execute more than once (several calls, or a loop)
     */
    private record Unit(List<Call> calls, PsiCodeBlock block, PsiStatement first, PsiStatement last, boolean replays) {
    }

    /** How the kernel uses an array parameter. */
    private record Use(boolean read, boolean write) {
        Use or(Use other) {
            return new Use(read || other.read, write || other.write);
        }
    }

    /**
     * Rewrites the calls to {@code original} in its file to launch {@code kernel}, which will
     * replace it under the same name. Called before the kernel is placed, while the calls still
     * resolve to {@code original}.
     *
     * @param kernel the kernel, parsed in the context of {@code original}'s class
     */
    static @NotNull CallSites rewrite(@NotNull PsiMethod original, @NotNull PsiMethod kernel) {
        Set<PsiElement> owners = new HashSet<>();
        int rewritten = 0;
        int graphs = 0;
        // One unit at a time: each rewrite moves statements, so the calls are found again after it
        while (true) {
            List<Call> calls = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            scan(original, kernel, calls, skipped);
            if (calls.isEmpty()) {
                return new CallSites(rewritten, owners.size(), graphs, skipped);
            }
            Call first = calls.get(0);
            List<Call> group = calls.stream().filter(call -> call.owner() == first.owner() && call.key().equals(first.key())).toList();
            if (braceBodies(group)) {
                // A call that is the whole body of a loop or an if is put in a block first, then found again
                continue;
            }
            Unit unit = unit(group);
            launch(unit, original, kernel);
            rewritten += unit.calls().size();
            graphs++;
            owners.add(first.owner());
        }
    }

    // The calls to the original in its file, in the order of the file, and why the others cannot be rewritten
    private static void scan(PsiMethod original, PsiMethod kernel, List<Call> calls, List<String> skipped) {
        PsiFile file = original.getContainingFile();
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)) {
            if (!original.getName().equals(call.getMethodExpression().getReferenceName())
                    || !sameMethod(call.resolveMethod(), original) || PsiTreeUtil.isAncestor(original, call, true)) {
                continue;
            }
            PsiElement owner = PsiTreeUtil.getParentOfType(call, PsiMethod.class, PsiLambdaExpression.class, PsiClassInitializer.class);
            String why = skipReason(call, owner, kernel);
            if (why != null) {
                skipped.add(why);
                continue;
            }
            calls.add(new Call(call, (PsiExpressionStatement) call.getParent(), owner, key(call, owner)));
        }
    }

    private static @Nullable String skipReason(PsiMethodCallExpression call, @Nullable PsiElement owner, PsiMethod kernel) {
        if (owner == null) {
            return MessageBundle.message("convert.calls.skip.notInMethod");
        }
        if (owner instanceof PsiMethod method && TornadoKernels.isKernel(method)) {
            return MessageBundle.message("convert.calls.skip.inKernel");
        }
        if (!(call.getParent() instanceof PsiExpressionStatement)) {
            return MessageBundle.message("convert.calls.skip.notStatement");
        }
        List<PsiParameter> parameters = taskParameters(kernel);
        PsiExpression[] arguments = call.getArgumentList().getExpressions();
        if (parameters.size() != arguments.length) {
            return MessageBundle.message("convert.calls.skip.parameters");
        }
        for (int i = 0; i < arguments.length; i++) {
            if (!isScalar(parameters.get(i).getType()) && variable(arguments[i]) == null) {
                return MessageBundle.message("convert.calls.skip.arrayExpression", arguments[i].getText());
            }
        }
        return null;
    }

    // The same arguments, as text and as the variables they name
    private static String key(PsiMethodCallExpression call, PsiElement owner) {
        StringBuilder key = new StringBuilder().append(System.identityHashCode(owner));
        for (PsiExpression argument : call.getArgumentList().getExpressions()) {
            key.append('|').append(argument.getText().replaceAll("\\s+", ""));
            for (PsiVariable variable : variables(argument)) {
                key.append('@').append(System.identityHashCode(variable));
            }
        }
        return key.toString();
    }

    // The calls of a group share a TaskGraph if nothing changes their arguments between them; otherwise the first goes alone
    private static Unit unit(List<Call> group) {
        Set<PsiVariable> variables = variables(group.get(0).call());
        boolean stable = stableArguments(group.get(0).call());
        // The most calls from the first on that can share a plan: a change to an argument ends the run
        for (int count = group.size(); count > 1 && stable; count--) {
            Unit shared = shared(group.subList(0, count), variables);
            if (shared != null) {
                return lift(shared, variables);
            }
        }
        Call call = group.get(0);
        PsiStatement statement = call.statement();
        Unit single = new Unit(List.of(call), (PsiCodeBlock) statement.getParent(), statement, statement, false);
        return stable ? lift(single, variables) : single;
    }

    private static @Nullable Unit shared(List<Call> group, Set<PsiVariable> variables) {
        PsiElement common = PsiTreeUtil.findCommonParent(group.stream().map(Call::statement).toArray(PsiElement[]::new));
        PsiCodeBlock block = common instanceof PsiCodeBlock codeBlock ? codeBlock : PsiTreeUtil.getParentOfType(common, PsiCodeBlock.class);
        if (block == null || !PsiTreeUtil.isAncestor(group.get(0).owner(), block, true)) {
            return null;
        }
        PsiStatement first = childOf(block, group.get(0).statement());
        PsiStatement last = childOf(block, group.get(group.size() - 1).statement());
        if (first == null || last == null) {
            return null;
        }
        List<PsiElement> range = range(first, last);
        Set<PsiMethodCallExpression> own = new HashSet<>();
        for (Call call : group) {
            own.add(call.call());
        }
        for (PsiElement element : range) {
            if (!invariant(element, variables, own)) {
                return null;
            }
        }
        return new Unit(group, block, first, last, true);
    }

    // Moves the TaskGraph before the loops around the unit that do not change its arguments
    private static Unit lift(Unit unit, Set<PsiVariable> variables) {
        Set<PsiMethodCallExpression> own = new HashSet<>();
        for (Call call : unit.calls()) {
            own.add(call.call());
        }
        PsiElement owner = unit.calls().get(0).owner();
        Unit best = unit;
        for (PsiElement parent = unit.block(); parent != null && parent != owner; parent = parent.getParent()) {
            if (parent instanceof PsiLambdaExpression || parent instanceof PsiClass) {
                break;
            }
            if (parent instanceof PsiLoopStatement loop) {
                if (!invariant(loop, variables, own) || declaresAny(loop, variables)) {
                    break;
                }
                if (loop.getParent() instanceof PsiCodeBlock block) {
                    best = new Unit(unit.calls(), block, loop, loop, true);
                }
            }
        }
        return best;
    }

    // Whether nothing in element assigns the variables, or, for a field of a scalar type, may assign it in a call
    private static boolean invariant(PsiElement element, Set<PsiVariable> variables, Set<PsiMethodCallExpression> own) {
        boolean scalarField = variables.stream().anyMatch(v -> v instanceof PsiField field
                && !field.hasModifierProperty(PsiModifier.FINAL) && isScalar(field.getType()));
        for (PsiElement inside : PsiTreeUtil.findChildrenOfAnyType(element, false, PsiAssignmentExpression.class,
                PsiUnaryExpression.class, PsiCallExpression.class)) {
            if (inside instanceof PsiAssignmentExpression assignment && variables.contains(variable(assignment.getLExpression()))) {
                return false;
            }
            if (inside instanceof PsiUnaryExpression unary && isIncrement(unary) && variables.contains(variable(unary.getOperand()))) {
                return false;
            }
            if (inside instanceof PsiCallExpression call && scalarField && !own.contains(call)) {
                return false;
            }
        }
        return true;
    }

    private static boolean declaresAny(PsiElement element, Set<PsiVariable> variables) {
        for (PsiVariable variable : variables) {
            if (PsiTreeUtil.isAncestor(element, variable, true)) {
                return true;
            }
        }
        return false;
    }

    // Arguments that give the same values when evaluated again: variables, literals and operators on them
    private static boolean stableArguments(PsiMethodCallExpression call) {
        for (PsiExpression argument : call.getArgumentList().getExpressions()) {
            if (!PsiTreeUtil.findChildrenOfAnyType(argument, false, PsiCallExpression.class, PsiAssignmentExpression.class).isEmpty()) {
                return false;
            }
            for (PsiUnaryExpression unary : PsiTreeUtil.findChildrenOfType(argument, PsiUnaryExpression.class)) {
                if (isIncrement(unary)) {
                    return false;
                }
            }
            if (argument instanceof PsiUnaryExpression unary && isIncrement(unary)) {
                return false;
            }
        }
        return true;
    }

    // Builds the TaskGraph and the plan before the unit, moves it into the plan's try block, and replays the plan at each call
    private static void launch(Unit unit, PsiMethod original, PsiMethod kernel) {
        PsiFile file = original.getContainingFile();
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(file.getProject());
        JavaCodeStyleManager styles = JavaCodeStyleManager.getInstance(file.getProject());
        PsiCodeBlock block = unit.block();
        PsiStatement first = unit.first();
        PsiStatement last = unit.last();
        PsiMethodCallExpression call = unit.calls().get(0).call();
        PsiExpression[] arguments = call.getArgumentList().getExpressions();
        List<PsiParameter> parameters = taskParameters(kernel);

        String graphName = graphName(file);
        String taskGraph = styles.suggestUniqueVariableName("taskGraph", first, true);
        String plan = styles.suggestUniqueVariableName("executionPlan", first, true);
        List<String> before = new ArrayList<>();
        List<String> taskArguments = new ArrayList<>();
        String context = contextType(kernel);
        KernelGrid grid = context == null ? null : KernelGrid.parse(kernel.getText());
        String scheduler = null;
        if (context != null) {
            String contextName = styles.suggestUniqueVariableName(context.endsWith("TileContext") ? "tileContext" : "context", first, true);
            before.add(context + " " + contextName + " = new " + context + "();");
            taskArguments.add(contextName);
        }
        if (grid != null && !grid.global().isEmpty()) {
            String workerGrid = styles.suggestUniqueVariableName("workerGrid", first, true);
            scheduler = styles.suggestUniqueVariableName("gridScheduler", first, true);
            String gridType = API + "WorkerGrid" + grid.global().size() + "D";
            List<String> sizes = new ArrayList<>();
            for (int i = 0; i < grid.global().size(); i++) {
                String size = substitute(grid.global().get(i), parameters, arguments);
                sizes.add(i < grid.local().size() ? roundUp(size, substitute(grid.local().get(i), parameters, arguments)) : size);
            }
            before.add(gridType + " " + workerGrid + " = new " + gridType + "(" + String.join(", ", sizes) + ");");
            if (!grid.local().isEmpty()) {
                List<String> local = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    local.add(i < grid.local().size() ? substitute(grid.local().get(i), parameters, arguments) : "1");
                }
                before.add(workerGrid + ".setLocalWork(" + String.join(", ", local) + ");");
            }
            before.add(API + "GridScheduler " + scheduler + " = new " + API + "GridScheduler(\"" + graphName + "." + TASK + "\", "
                    + workerGrid + ");");
        }

        // What the kernel reads and writes, and what the code changes between executions
        Set<PsiMethodCallExpression> own = new HashSet<>();
        for (Call c : unit.calls()) {
            own.add(c.call());
        }
        List<PsiElement> range = range(first, last);
        Map<PsiVariable, String> names = new LinkedHashMap<>();
        Map<PsiVariable, Use> uses = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i++) {
            taskArguments.add(arguments[i].getText());
            PsiParameter parameter = parameters.get(i);
            if (isScalar(parameter.getType())) {
                continue;
            }
            PsiVariable variable = Objects.requireNonNull(variable(arguments[i]));
            names.putIfAbsent(variable, arguments[i].getText());
            uses.merge(variable, use(kernel, parameter), Use::or);
        }
        List<String> firstExecution = new ArrayList<>();
        List<String> everyExecution = new ArrayList<>();
        List<String> toHost = new ArrayList<>();
        for (Map.Entry<PsiVariable, Use> entry : uses.entrySet()) {
            Use use = entry.getValue();
            String name = names.get(entry.getKey());
            boolean changed = unit.replays() && hostWrites(range, entry.getKey(), own);
            if (use.read() || changed || !use.write()) {
                (changed ? everyExecution : firstExecution).add(name);
            }
            if (use.write()) {
                toHost.add(name);
            }
        }
        String owner = Objects.requireNonNull(original.getContainingClass()).getQualifiedName();
        StringBuilder graph = new StringBuilder(API + "TaskGraph " + taskGraph + " = new " + API + "TaskGraph(\"" + graphName + "\")");
        if (!firstExecution.isEmpty()) {
            graph.append("\n.transferToDevice(" + API + "enums.DataTransferMode.FIRST_EXECUTION, ").append(String.join(", ", firstExecution)).append(')');
        }
        if (!everyExecution.isEmpty()) {
            graph.append("\n.transferToDevice(" + API + "enums.DataTransferMode.EVERY_EXECUTION, ").append(String.join(", ", everyExecution)).append(')');
        }
        graph.append("\n.task(\"" + TASK + "\", ").append(owner).append("::").append(original.getName());
        for (String argument : taskArguments) {
            graph.append(", ").append(argument);
        }
        graph.append(')');
        if (!toHost.isEmpty()) {
            graph.append("\n.transferToHost(" + API + "enums.DataTransferMode.EVERY_EXECUTION, ").append(String.join(", ", toHost)).append(')');
        }
        before.add(graph.append(';').toString());

        // The variables declared between the calls and used after them are declared before the try block
        if (first != last) {
            before.addAll(hoistDeclarations(range, last, unit.calls().get(0).owner(), factory));
        }
        for (Call c : unit.calls()) {
            PsiElement replaced = c.statement().replace(factory.createStatementFromText(plan + ".execute();", c.statement()));
            if (c.statement() == first) {
                first = (PsiStatement) replaced;
            }
            if (c.statement() == last) {
                last = (PsiStatement) replaced;
            }
        }
        String withScheduler = scheduler == null ? "" : plan + ".withGridScheduler(" + scheduler + ");\n";
        PsiTryStatement template = (PsiTryStatement) factory.createStatementFromText("try (" + API + "TornadoExecutionPlan " + plan
                + " = new " + API + "TornadoExecutionPlan(" + taskGraph + ".snapshot())) {\n" + withScheduler + "}", first);
        List<PsiElement> added = new ArrayList<>();
        for (String text : before) {
            added.add(block.addBefore(factory.createStatementFromText(text, first), first));
        }
        PsiTryStatement tryStatement = (PsiTryStatement) block.addBefore(template, first);
        PsiCodeBlock body = Objects.requireNonNull(tryStatement.getTryBlock());
        body.addRangeBefore(first, last, body.getRBrace());
        block.deleteChildRange(first, last);
        handleException(tryStatement, factory, styles);
        added.add(tryStatement);

        CodeStyleManager formatter = CodeStyleManager.getInstance(file.getProject());
        for (PsiElement element : added) {
            if (element.isValid()) {
                formatter.reformat(styles.shortenClassReferences(element));
            }
        }
    }

    // Splits the declarations of the variables that the code after the unit uses: declared before the try block, assigned in it
    private static List<String> hoistDeclarations(List<PsiElement> range, PsiStatement last, PsiElement owner, PsiElementFactory factory) {
        List<String> declarations = new ArrayList<>();
        int end = last.getTextRange().getEndOffset();
        for (PsiElement element : range) {
            if (!(element instanceof PsiDeclarationStatement declaration)) {
                continue;
            }
            List<PsiLocalVariable> locals = new ArrayList<>();
            boolean usedAfter = false;
            for (PsiElement declared : declaration.getDeclaredElements()) {
                if (declared instanceof PsiLocalVariable local) {
                    locals.add(local);
                    for (PsiReference reference : ReferencesSearch.search(local, new LocalSearchScope(owner)).findAll()) {
                        usedAfter |= reference.getElement().getTextRange().getStartOffset() >= end;
                    }
                }
            }
            if (!usedAfter) {
                continue;
            }
            List<String> assignments = new ArrayList<>();
            for (PsiLocalVariable local : locals) {
                String type = local.getType().getCanonicalText();
                declarations.add(type + " " + local.getName() + ";");
                PsiExpression initializer = local.getInitializer();
                if (initializer instanceof PsiArrayInitializerExpression) {
                    assignments.add(local.getName() + " = new " + type + " " + initializer.getText() + ";");
                } else if (initializer != null) {
                    assignments.add(local.getName() + " = " + initializer.getText() + ";");
                }
            }
            for (String assignment : assignments) {
                declaration.getParent().addBefore(factory.createStatementFromText(assignment, declaration), declaration);
            }
            declaration.delete();
        }
        return declarations;
    }

    // As the tep template does: closing the plan throws a checked exception, rethrown unchecked if the code does not handle it
    private static void handleException(PsiTryStatement tryStatement, PsiElementFactory factory, JavaCodeStyleManager styles) {
        PsiClass exception = JavaPsiFacade.getInstance(tryStatement.getProject()).findClass(EXCEPTION, tryStatement.getResolveScope());
        if (exception == null || tryStatement.getResourceList() == null) {
            return;
        }
        PsiClassType type = factory.createType(exception);
        if (ExceptionUtil.isHandled(type, tryStatement.getResourceList())) {
            return;
        }
        String name = styles.suggestUniqueVariableName("e", tryStatement, false);
        PsiCatchSection section = (PsiCatchSection) tryStatement.add(factory.createCatchSection(type, name, tryStatement));
        PsiCodeBlock body = section.getCatchBlock();
        if (body != null) {
            for (PsiStatement statement : body.getStatements()) {
                statement.delete();
            }
            body.add(factory.createStatementFromText("throw new RuntimeException(" + name + ");", body));
        }
    }

    /** How {@code kernel} uses {@code parameter}: read, written, or both if it cannot tell (a helper, an alias). */
    private static Use use(PsiMethod kernel, PsiParameter parameter) {
        Use use = new Use(false, false);
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(kernel, PsiReferenceExpression.class)) {
            if (reference.resolve() != parameter) {
                continue;
            }
            PsiElement parent = PsiUtil.skipParenthesizedExprUp(reference.getParent());
            if (parent instanceof PsiArrayAccessExpression access && PsiUtil.skipParenthesizedExprDown(access.getArrayExpression()) == reference) {
                PsiExpression element = access;
                while (PsiUtil.skipParenthesizedExprUp(element.getParent()) instanceof PsiArrayAccessExpression outer
                        && PsiUtil.skipParenthesizedExprDown(outer.getArrayExpression()) == element) {
                    element = outer;
                }
                PsiElement context = PsiUtil.skipParenthesizedExprUp(element.getParent());
                if (context instanceof PsiAssignmentExpression assignment
                        && PsiUtil.skipParenthesizedExprDown(assignment.getLExpression()) == element) {
                    use = use.or(new Use(assignment.getOperationTokenType() != JavaTokenType.EQ, true));
                } else if (context instanceof PsiUnaryExpression unary && isIncrement(unary)) {
                    use = use.or(new Use(true, true));
                } else {
                    use = use.or(new Use(true, false));
                }
            } else if (parent instanceof PsiReferenceExpression member && member.getQualifierExpression() != null
                    && PsiUtil.skipParenthesizedExprDown(member.getQualifierExpression()) == reference) {
                String name = member.getReferenceName();
                if (!(member.getParent() instanceof PsiMethodCallExpression)) {
                    // x.length is not the contents
                    use = "length".equals(name) ? use : use.or(new Use(true, true));
                } else if ("getSize".equals(name) || "getNumElements".equals(name)) {
                    continue;
                } else if (name != null && name.startsWith("get")) {
                    use = use.or(new Use(true, false));
                } else if ("set".equals(name)) {
                    use = use.or(new Use(false, true));
                } else {
                    use = use.or(new Use(true, true));
                }
            } else {
                // An argument of a helper or of atomicAdd, an alias: anything
                use = use.or(new Use(true, true));
            }
        }
        return use.read() || use.write() ? use : new Use(true, false);
    }

    // Whether the code between the executions (not the calls themselves) may change the array's contents
    private static boolean hostWrites(List<PsiElement> range, PsiVariable array, Set<PsiMethodCallExpression> own) {
        for (PsiElement element : range) {
            for (PsiElement inside : PsiTreeUtil.findChildrenOfAnyType(element, false, PsiAssignmentExpression.class,
                    PsiUnaryExpression.class, PsiMethodCallExpression.class)) {
                if (inside instanceof PsiAssignmentExpression assignment && root(assignment.getLExpression()) == array
                        && assignment.getLExpression() instanceof PsiArrayAccessExpression) {
                    return true;
                }
                if (inside instanceof PsiUnaryExpression unary && isIncrement(unary)
                        && unary.getOperand() instanceof PsiArrayAccessExpression && root(unary.getOperand()) == array) {
                    return true;
                }
                if (inside instanceof PsiMethodCallExpression call && !own.contains(call)) {
                    PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                    String name = call.getMethodExpression().getReferenceName();
                    if (qualifier != null && variable(qualifier) == array && name != null && !name.startsWith("get")
                            && !name.equals("length") && !name.equals("size")) {
                        return true;
                    }
                    for (PsiExpression argument : call.getArgumentList().getExpressions()) {
                        if (variable(argument) == array) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // The variable an array element belongs to: a in a[i][j]
    private static @Nullable PsiVariable root(@Nullable PsiExpression expression) {
        PsiExpression current = PsiUtil.skipParenthesizedExprDown(expression);
        while (current instanceof PsiArrayAccessExpression access) {
            current = PsiUtil.skipParenthesizedExprDown(access.getArrayExpression());
        }
        return variable(current);
    }

    // The kernel's parameters that the task passes from the call: all but a leading context
    private static List<PsiParameter> taskParameters(PsiMethod kernel) {
        List<PsiParameter> parameters = new ArrayList<>(List.of(kernel.getParameterList().getParameters()));
        if (contextType(kernel) != null) {
            parameters.remove(0);
        }
        return parameters;
    }

    // The qualified name of the kernel's context class, or null if it takes none
    private static @Nullable String contextType(PsiMethod kernel) {
        PsiParameter[] parameters = kernel.getParameterList().getParameters();
        if (parameters.length == 0) {
            return null;
        }
        PsiType type = parameters[0].getType();
        if (TornadoKernels.isKernelContextType(type)) {
            return TornadoKernels.KERNEL_CONTEXT;
        }
        return TornadoKernels.isTileContextType(type) ? TornadoKernels.TILE_CONTEXT : null;
    }

    // A grid size over the kernel's parameters, over the call's arguments instead
    static @NotNull String substitute(@NotNull String size, @NotNull List<PsiParameter> parameters, @NotNull PsiExpression[] arguments) {
        String result = size;
        for (int i = 0; i < parameters.size() && i < arguments.length; i++) {
            String argument = arguments[i].getText();
            String replacement = argument.matches("[\\w.]+") ? argument : "(" + argument + ")";
            result = result.replaceAll("(?<![\\w.])" + parameters.get(i).getName() + "\\b", java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    // The grid size rounded up to a multiple of the work-group size, as TornadoVM needs
    static @NotNull String roundUp(@NotNull String size, @NotNull String local) {
        if (size.matches("\\d+") && local.matches("\\d+")) {
            long l = Long.parseLong(local);
            return String.valueOf((Long.parseLong(size) + l - 1) / l * l);
        }
        String operand = size.matches("[\\w.]+") ? size : "(" + size + ")";
        if (local.matches("\\d+")) {
            return "(" + operand + " + " + (Long.parseLong(local) - 1) + ") / " + local + " * " + local;
        }
        return "(" + operand + " + " + local + " - 1) / " + local + " * " + local;
    }

    // "s0", "s1", ...: the first that no TaskGraph of the file is named
    private static String graphName(PsiFile file) {
        Set<String> used = new HashSet<>();
        for (PsiNewExpression construction : PsiTreeUtil.findChildrenOfType(file, PsiNewExpression.class)) {
            if (construction.getClassReference() != null && "TaskGraph".equals(construction.getClassReference().getReferenceName())
                    && construction.getArgumentList() != null && construction.getArgumentList().getExpressions().length > 0
                    && construction.getArgumentList().getExpressions()[0] instanceof PsiLiteralExpression literal
                    && literal.getValue() instanceof String name) {
                used.add(name);
            }
        }
        int i = 0;
        while (used.contains("s" + i)) {
            i++;
        }
        return "s" + i;
    }

    private static boolean isScalar(PsiType type) {
        return type instanceof PsiPrimitiveType || PsiPrimitiveType.getUnboxedType(type) != null
                || type.equalsToText("java.lang.String");
    }

    private static boolean isIncrement(PsiUnaryExpression unary) {
        return unary.getOperationTokenType() == JavaTokenType.PLUSPLUS || unary.getOperationTokenType() == JavaTokenType.MINUSMINUS;
    }

    private static @Nullable PsiVariable variable(@Nullable PsiExpression expression) {
        return PsiUtil.skipParenthesizedExprDown(expression) instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiVariable variable ? variable : null;
    }

    private static Set<PsiVariable> variables(PsiElement element) {
        Set<PsiVariable> variables = new LinkedHashSet<>();
        for (PsiReferenceExpression reference : PsiTreeUtil.findChildrenOfType(element, PsiReferenceExpression.class)) {
            if (reference.resolve() instanceof PsiVariable variable) {
                variables.add(variable);
            }
        }
        if (element instanceof PsiReferenceExpression reference && reference.resolve() instanceof PsiVariable variable) {
            variables.add(variable);
        }
        return variables;
    }

    private static Set<PsiVariable> variables(PsiMethodCallExpression call) {
        Set<PsiVariable> variables = new LinkedHashSet<>();
        for (PsiExpression argument : call.getArgumentList().getExpressions()) {
            variables.addAll(variables(argument));
        }
        return variables;
    }

    // Puts each call that is not in a block, such as the whole body of a loop, in braces; true if it did
    private static boolean braceBodies(List<Call> group) {
        boolean braced = false;
        for (Call call : group) {
            PsiStatement statement = call.statement();
            if (!(statement.getParent() instanceof PsiCodeBlock)) {
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(statement.getProject());
                PsiStatement block = factory.createStatementFromText("{\n" + statement.getText() + "\n}", statement);
                CodeStyleManager.getInstance(statement.getProject()).reformat(statement.replace(block));
                braced = true;
            }
        }
        return braced;
    }

    private static @Nullable PsiStatement childOf(PsiCodeBlock block, PsiElement element) {
        PsiElement current = element;
        while (current != null && current.getParent() != block) {
            current = current.getParent();
        }
        return current instanceof PsiStatement statement ? statement : null;
    }

    private static List<PsiElement> range(PsiStatement first, PsiStatement last) {
        List<PsiElement> range = new ArrayList<>();
        for (PsiElement element = first; element != null; element = element.getNextSibling()) {
            range.add(element);
            if (element == last) {
                break;
            }
        }
        return range;
    }

    // The same method: in a copy of the file, a qualified call resolves to the method in the file itself
    private static boolean sameMethod(@Nullable PsiMethod resolved, PsiMethod original) {
        if (resolved == null) {
            return false;
        }
        if (resolved == original) {
            return true;
        }
        PsiClass a = resolved.getContainingClass();
        PsiClass b = original.getContainingClass();
        if (a == null || b == null || !Objects.equals(a.getQualifiedName(), b.getQualifiedName())
                || !resolved.getName().equals(original.getName())) {
            return false;
        }
        PsiParameter[] x = resolved.getParameterList().getParameters();
        PsiParameter[] y = original.getParameterList().getParameters();
        if (x.length != y.length) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            if (!x[i].getType().getCanonicalText().equals(y[i].getType().getCanonicalText())) {
                return false;
            }
        }
        return true;
    }
}
