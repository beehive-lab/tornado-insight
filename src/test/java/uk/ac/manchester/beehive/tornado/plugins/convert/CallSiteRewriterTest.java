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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;

import java.util.List;

// The rewritten code of these cases was also compiled against TornadoVM 8.2.0 and run on a GPU,
// with the same results as the original methods
public class CallSiteRewriterTest extends TornadoTestCase {

    private static final String METHOD = """
                public static void matrixMultiply(float[] a, float[] b, float[] c, int size) {
                    for (int i = 0; i < size; i++) {
                        for (int j = 0; j < size; j++) {
                            float sum = 0.0f;
                            for (int k = 0; k < size; k++) {
                                sum += a[i * size + k] * b[k * size + j];
                            }
                            c[i * size + j] = sum;
                        }
                    }
                }""";

    private static final String KERNEL = METHOD.replace("for (int i", "for (@Parallel int i").replace("for (int j", "for (@Parallel int j");

    private static final String KERNEL_API = """
            public static void matrixMultiply(KernelContext context, float[] a, float[] b, float[] c, int size) {
                // grid: size, size
                // local: 16, 16
                int j = context.globalIdx;
                int i = context.globalIdy;
                if (i < size && j < size) {
                    float sum = 0.0f;
                    for (int k = 0; k < size; k++) {
                        sum += a[i * size + k] * b[k * size + j];
                    }
                    c[i * size + j] = sum;
                }
            }""";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        addSystemStubs();
        myFixture.addClass("package java.lang; public class Object { }");
        myFixture.addClass("package java.lang; public class Throwable { }");
        myFixture.addClass("package java.lang; public class Exception extends Throwable { }");
        myFixture.addClass("package java.lang; public class RuntimeException extends Exception { public RuntimeException(Throwable cause) { } }");
    }

    public void testTheFirstCallBuildsThePlanAndTheNextReplayIt() {
        KernelReplacement.Rewritten rewritten = rewrite("""
                static void run(float[] a, float[] b, float[] c, int size) {
                    matrixMultiply(a, b, c, size);
                    System.out.println(c[0]);
                    matrixMultiply(a, b, c, size);
                }""", KERNEL);
        assertEquals(new CallSites(2, 1, 1, List.of()), rewritten.calls());
        assertEquals("""
                static void run(float[] a, float[] b, float[] c, int size) {
                    TaskGraph taskGraph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                            .task("t0", Example::matrixMultiply, a, b, c, size)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
                        executionPlan.execute();
                        System.out.println(c[0]);
                        executionPlan.execute();
                    } catch (TornadoExecutionPlanException e) {
                        throw new RuntimeException(e);
                    }
                }""", run(rewritten));
        assertContains(rewritten.text(), """
                import uk.ac.manchester.tornado.api.TaskGraph;
                import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.enums.DataTransferMode;
                import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
                """);
        assertTrue(rewritten.text().contains("for (@Parallel int i = 0"));
    }

    public void testAcceptGivesThePreview() {
        String callers = """
                static void run(float[] a, float[] b, float[] c, int size) {
                    matrixMultiply(a, b, c, size);
                    matrixMultiply(a, b, c, size);
                }""";
        KernelReplacement.Rewritten rewritten = rewrite(callers, KERNEL);
        PsiMethod original = methodAtCaret();
        WriteCommandAction.writeCommandAction(getProject()).run(() ->
                KernelReplacement.place(original, KERNEL, KernelReplacement.Placement.REPLACE, true));
        assertEquals(rewritten.text(), myFixture.getFile().getText());
    }

    public void testACallInALoopGetsItsPlanBeforeTheLoop() {
        String run = run(rewrite("""
                static void run(float[] a, float[] b, float[] c, int size, int iterations) {
                    for (int it = 0; it < iterations; it++) {
                        matrixMultiply(a, b, c, size);
                    }
                }""", KERNEL));
        assertContains(run, """
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
                        for (int it = 0; it < iterations; it++) {
                            executionPlan.execute();
                        }
                    }""");
    }

    public void testAnInputTheCodeChangesBetweenExecutionsIsTransferredEachTime() {
        String run = run(rewrite("""
                static void run(float[] a, float[] b, float[] c, int size) {
                    matrixMultiply(a, b, c, size);
                    a[0] = 2.0f;
                    matrixMultiply(a, b, c, size);
                }""", KERNEL));
        assertContains(run, """
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, b)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, a)""");
    }

    public void testOtherArgumentsGetTheirOwnTaskGraphAndTheValueOfALambdaIsLeft() {
        KernelReplacement.Rewritten rewritten = rewrite("""
                static void run(float[] a, float[] b, float[] c, int size) {
                    matrixMultiply(a, b, c, size);
                    matrixMultiply(b, a, c, size);
                    for (int i = 0; i < 3; i++) matrixMultiply(a, b, c, i);
                    Runnable r = () -> matrixMultiply(a, b, c, size);
                }""", KERNEL);
        CallSites calls = rewritten.calls();
        assertEquals(3, calls.rewritten());
        assertEquals(3, calls.graphs());
        assertEquals(List.of("its value is used, or it is not a statement of its own"), calls.skipped());
        String run = run(rewritten);
        assertTrue(run, run.contains("new TaskGraph(\"s1\")"));
        // The loop's index changes, so the plan is built in the loop, now in braces
        assertContains(run, """
                    for (int i = 0; i < 3; i++) {
                        TaskGraph taskGraph2 = new TaskGraph("s2")""");
        assertTrue(run, run.contains("Runnable r = () -> matrixMultiply(a, b, c, size);"));
    }

    public void testAChangedArgumentEndsTheReplaysAndVariablesUsedAfterThemAreDeclaredBefore() {
        KernelReplacement.Rewritten rewritten = rewrite("""
                static void run(float[] a, float[] b, float[] c, int size) throws uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException {
                    matrixMultiply(a, b, c, size);
                    float first = c[0];
                    matrixMultiply(a, b, c, size);
                    System.out.println(first);
                    size = size / 2;
                    matrixMultiply(a, b, c, size);
                }""", KERNEL);
        assertEquals(new CallSites(3, 1, 2, List.of()), rewritten.calls());
        // The method declares the plan's exception, so nothing catches it
        assertEquals("""
                static void run(float[] a, float[] b, float[] c, int size) throws uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException {
                    TaskGraph taskGraph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                            .task("t0", Example::matrixMultiply, a, b, c, size)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
                    float first;
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
                        executionPlan.execute();
                        first = c[0];
                        executionPlan.execute();
                    }
                    System.out.println(first);
                    size = size / 2;
                    TaskGraph taskGraph1 = new TaskGraph("s1")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                            .task("t0", Example::matrixMultiply, a, b, c, size)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph1.snapshot())) {
                        executionPlan.execute();
                    }
                }""", run(rewritten));
    }

    public void testAKernelApiKernelIsLaunchedOnItsGrid() {
        String run = run(rewrite("""
                static void run(float[] a, float[] b, float[] c, int n) {
                    matrixMultiply(a, b, c, n);
                    matrixMultiply(a, b, c, n);
                }""", KERNEL_API));
        assertContains(run, """
                    KernelContext context = new KernelContext();
                    WorkerGrid2D workerGrid = new WorkerGrid2D((n + 15) / 16 * 16, (n + 15) / 16 * 16);
                    workerGrid.setLocalWork(16, 16, 1);
                    GridScheduler gridScheduler = new GridScheduler("s0.t0", workerGrid);
                    TaskGraph taskGraph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                            .task("t0", Example::matrixMultiply, context, a, b, c, n)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
                        executionPlan.withGridScheduler(gridScheduler);
                        executionPlan.execute();
                        executionPlan.execute();
                    }""");
    }

    public void testTheCodeAroundTheCallsIsKeptAndTaskGraphNamesAreNotReused() {
        myFixture.configureByText("Example.java", """
                import uk.ac.manchester.tornado.api.TaskGraph;
                import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
                import uk.ac.manchester.tornado.api.enums.DataTransferMode;
                import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;

                public class Example {
                    public static void matrix<caret>Multiply(FloatArray a, FloatArray b, FloatArray c, int size) {
                        for (int i = 0; i < size; i++) {
                            for (int j = 0; j < size; j++) {
                                float sum = 0.0f;
                                for (int k = 0; k < size; k++) {
                                    sum += a.get(i * size + k) * b.get(k * size + j);
                                }
                                c.set(i * size + j, sum);
                            }
                        }
                    }

                    public static void main(String[] args) throws TornadoExecutionPlanException {
                        FloatArray a = null, b = null, c = null, expected = null;
                        int size = 256;
                        TaskGraph taskGraph = new TaskGraph("s0") //
                                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b) //
                                .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
                        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
                            // Kept as it is
                            executionPlan.execute();
                        }

                        matrixMultiply(a, b, expected, size);

                        matrixMultiply(a, b, expected, size);
                    }
                }
                """);
        PsiMethod original = methodAtCaret();
        String kernel = original.getText().replace("for (int i", "for (@Parallel int i").replace("for (int j", "for (@Parallel int j");
        KernelReplacement.Rewritten rewritten = KernelReplacement.previewWithCalls(original, kernel);
        assertNotNull(rewritten);
        assertContains(rewritten.text(), """
                        TaskGraph taskGraph = new TaskGraph("s0") //
                                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b) //
                                .transferToHost(DataTransferMode.EVERY_EXECUTION, c);
                        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph.snapshot())) {
                            // Kept as it is
                            executionPlan.execute();
                        }

                        TaskGraph taskGraph1 = new TaskGraph("s1")
                                .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                                .task("t0", Example::matrixMultiply, a, b, expected, size)
                                .transferToHost(DataTransferMode.EVERY_EXECUTION, expected);
                        try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(taskGraph1.snapshot())) {
                            executionPlan.execute();

                            executionPlan.execute();
                        }
                    }""");
    }

    public void testCallsInKernelsAndFieldsAreLeftWithTheirReasons() {
        KernelReplacement.Rewritten rewritten = rewrite("""
                static float[] x = new float[4];

                public static void other(float[] a, float[] b, float[] c) {
                    for (@uk.ac.manchester.tornado.api.annotations.Parallel int i = 0; i < 1; i++) {
                        matrixMultiply(a, b, c, 1);
                    }
                }

                static void run(float[] c) {
                    matrixMultiply(new float[4], x, c, 2);
                }""", KERNEL);
        assertEquals(0, rewritten.calls().rewritten());
        assertEquals(List.of("it is in a kernel", "the argument new float[4] is not a variable, so the TaskGraph cannot transfer it"),
                rewritten.calls().skipped());
    }

    public void testCallsInDifferentCasesDoNotShareAPlan() {
        KernelReplacement.Rewritten rewritten = rewrite("""
                static void run(float[] a, float[] b, float[] c, int size, int mode) {
                    switch (mode) {
                        case 0:
                            matrixMultiply(a, b, c, size);
                            break;
                        case 1:
                            matrixMultiply(a, b, c, size);
                            break;
                    }
                }""", KERNEL);
        // One TaskGraph per case: a shared try block would take the case label in between
        assertEquals(2, rewritten.calls().graphs());
        assertContains(run(rewritten), """
                    case 1:
                        TaskGraph taskGraph1 = new TaskGraph("s1")""");
    }

    public void testNoTaskGraphIsBuiltWhereTheCallMayNotRun() {
        String run = run(rewrite("""
                static void run(float[] a, float[] b, float[] c, int size, int k, boolean ready) {
                    for (int i = 0; i < k; i++) {
                        if (ready) {
                            matrixMultiply(a, b, c, size);
                        }
                    }
                    if (ready) {
                        matrixMultiply(b, a, c, size);
                    }
                    matrixMultiply(b, a, c, size);
                }""", KERNEL));
        // Not out of the if into the loop, and not shared from a call in an if with the call after it
        assertContains(run, """
                    for (int i = 0; i < k; i++) {
                        if (ready) {
                            TaskGraph taskGraph = new TaskGraph("s0")""");
        assertContains(run, """
                    if (ready) {
                        TaskGraph taskGraph = new TaskGraph("s1")""");
        assertTrue(run, run.contains("new TaskGraph(\"s2\")"));
    }

    public void testArgumentsThatCanThrowAreNotEvaluatedBeforeALoop() {
        String run = run(rewrite("""
                static void run(float[] a, float[] b, float[] c, int k) {
                    for (int i = 0; i < k; i++) {
                        matrixMultiply(a, b, c, a.length / k);
                    }
                }""", KERNEL));
        assertContains(run, """
                    for (int i = 0; i < k; i++) {
                        TaskGraph taskGraph = new TaskGraph("s0")""");
    }

    public void testChangesThroughAnAliasALambdaOrACalledMethodAreTransferredEachTime() {
        String run = run(rewrite("""
                static float[] data = new float[4];

                static void refill() {
                }

                static void run(float[] a, float[] b, float[] c, int size) {
                    matrixMultiply(a, b, c, size);
                    float[] alias = a;
                    alias[0] = 1.0f;
                    Runnable r = () -> b[0] = 2.0f;
                    matrixMultiply(a, b, c, size);
                    matrixMultiply(data, b, c, size);
                    refill();
                    matrixMultiply(data, b, c, size);
                }""", KERNEL));
        assertContains(run, """
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b)
                            .task("t0", Example::matrixMultiply, a, b, c, size)""");
        // refill() may assign data: no replay across it
        assertTrue(run, run.contains("new TaskGraph(\"s2\")"));
    }

    public void testALocalClassDeclaredBetweenTheCallsStaysInScope() {
        KernelReplacement.Rewritten rewritten = rewrite("""
                static void run(float[] a, float[] b, float[] c, int size) {
                    matrixMultiply(a, b, c, size);
                    class Stat {
                    }
                    matrixMultiply(a, b, c, size);
                    new Stat();
                }""", KERNEL);
        assertEquals(2, rewritten.calls().graphs());
    }

    public void testGraphNamesGivenByConstantsAreNotReused() {
        String run = run(rewrite("""
                static final String NAME = "s0";

                static void run(float[] a, float[] b, float[] c, int size) {
                    new TaskGraph(NAME);
                    matrixMultiply(a, b, c, size);
                }""".replace("new TaskGraph", "new uk.ac.manchester.tornado.api.TaskGraph"), KERNEL));
        assertTrue(run, run.contains("new TaskGraph(\"s1\")"));
    }

    public void testTheUsersImportsAreKeptAsTheyAre() {
        myFixture.configureByText("Example.java", """
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;


                import uk.ac.manchester.tornado.api.types.arrays.IntArray;

                public class Example {
                %s

                    static void run(float[] a, float[] b, float[] c, int size) {
                        matrixMultiply(a, b, c, size);
                    }
                }
                """.formatted(METHOD.replace("matrixMultiply(float", "matrix<caret>Multiply(float")));
        KernelReplacement.Rewritten rewritten = KernelReplacement.previewWithCalls(methodAtCaret(), KERNEL);
        assertNotNull(rewritten);
        assertTrue(rewritten.text(), rewritten.text().contains("""
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;


                import uk.ac.manchester.tornado.api.types.arrays.IntArray;
                """));
    }

    public void testGridSizesAreRoundedUpOverTheArguments() {
        assertEquals("(n + 15) / 16 * 16", CallSiteRewriter.roundUp("n", "16"));
        assertEquals("(x.length + 255) / 256 * 256", CallSiteRewriter.roundUp("x.length", "256"));
        assertEquals("((a + b) + 7) / 8 * 8", CallSiteRewriter.roundUp("a + b", "8"));
        assertEquals("112", CallSiteRewriter.roundUp("100", "16"));
    }

    // Whether text contains snippet, line by line, whatever the indentation
    private static void assertContains(String text, String snippet) {
        assertTrue(text, strip(text).contains(strip(snippet)));
    }

    private static String strip(String text) {
        return text.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"));
    }

    private KernelReplacement.Rewritten rewrite(String callers, String kernel) {
        myFixture.configureByText("Example.java", "public class Example {\n"
                + METHOD.replace("matrixMultiply(float", "matrix<caret>Multiply(float") + "\n\n" + callers.indent(4) + "}\n");
        KernelReplacement.Rewritten rewritten = KernelReplacement.previewWithCalls(methodAtCaret(), kernel);
        assertNotNull(rewritten);
        return rewritten;
    }

    // The method run in the rewritten file, unindented
    private static String run(KernelReplacement.Rewritten rewritten) {
        String text = rewritten.text();
        int start = text.indexOf("    static void run(");
        int end = text.indexOf("\n    }\n", start) + "\n    }".length();
        return text.substring(start, end).stripIndent().strip();
    }

    private PsiMethod methodAtCaret() {
        return PsiTreeUtil.getParentOfType(myFixture.getFile().findElementAt(myFixture.getCaretOffset()), PsiMethod.class);
    }
}
