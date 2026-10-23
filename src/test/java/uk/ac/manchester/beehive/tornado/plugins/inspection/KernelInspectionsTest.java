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

package uk.ac.manchester.beehive.tornado.plugins.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.List;

/**
 * Highlighting tests for the kernel inspections, run with all of them enabled as in the IDE.
 */
public class KernelInspectionsTest extends TornadoTestCase {

    private static final String HEADER = """
            import uk.ac.manchester.tornado.api.KernelContext;
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            import uk.ac.manchester.tornado.api.annotations.Reduce;
            import uk.ac.manchester.tornado.api.types.arrays.IntArray;
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        addSystemStubs();
        myFixture.enableInspections(new DataTypeInspection(), new RecursionInspection(), new ThrowInspection(),
                new AssertInspection(), new SystemCallInspection(), new ExternalLibraryInspection(),
                new NonVoidReturnInspection(), new StaticTaskGraphInspection(), new UnanalysableHelperInspection());
    }

    public void testCleanKernelHasNoProblems() {
        configure("""
                class Demo {
                    static int twice(int x) { return 2 * x; }
                    static void add(int[] a, IntArray b, @Reduce int[] sum) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = twice(a[i]);
                            sum[0] += i;
                        }
                    }
                }""");

        assertEmpty(tornadoProblems());
    }

    public void testUnsupportedDataType() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            String s = "x";
                        }
                    }
                }""");

        HighlightInfo info = assertOne("inspection.datatype");
        assertEquals(HighlightSeverity.ERROR, info.getSeverity());
        assertEquals("String s = \"x\";", info.getText());
    }

    public void testSupportedAndUnsupportedDataTypes() {
        myFixture.addClass("package java.util.concurrent.atomic; public class AtomicInteger { }");
        myFixture.addClass("package java.lang; public final class Integer { }");
        myFixture.addClass("public class Int3 { }");
        configure("""
                import java.util.concurrent.atomic.AtomicInteger;
                class Demo {
                    static void kernel(int[] a, FloatArray f) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            long l = 1L; char c = 'c'; boolean b = true; short s = 1; byte y = 1; double d = 1;
                            int[][] grid = null; float[] fs = null; IntArray ia = null;
                            AtomicInteger counter = null; Int3 v = null;
                            Integer boxed = null;
                            String[] names = null;
                            Object o = null;
                        }
                    }
                }""".replace("FloatArray f", "uk.ac.manchester.tornado.api.types.arrays.FloatArray f"));

        List<String> flagged = problems("inspection.datatype").stream().map(HighlightInfo::getText).toList();
        // Object does not resolve in the light test project, so it is not classified
        assertEquals(List.of("Integer boxed = null;", "String[] names = null;"), flagged);
    }

    public void testRecursiveHelper() {
        configure("""
                class Demo {
                    static int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = fact(i);
                        }
                    }
                }""");

        // Reported once, on the self-call that closes the cycle
        HighlightInfo info = assertOne("inspection.recursion");
        assertEquals(HighlightSeverity.ERROR, info.getSeverity());
        assertEquals("fact(n - 1)", info.getText());
        assertTrue(info.getDescription().endsWith("(in helper 'Demo.fact' inlined into kernel)"));
    }

    public void testRecursiveKernel() {
        configure("""
                class Demo {
                    static void kernel(int[] a, int n) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = i;
                        }
                        if (n > 0) kernel(a, n - 1);
                    }
                }""");

        HighlightInfo info = assertOne("inspection.recursion");
        assertEquals("kernel(a, n - 1)", info.getText());
        assertEquals(MessageBundle.message("inspection.recursion"), info.getDescription());
    }

    public void testMutualRecursionThroughHelpersIsReportedOnce() {
        configure("""
                class Demo {
                    // Overridable instance methods: the IDE's own contract inference
                    // would otherwise trip over the mutual recursion in tests
                    int even(int n) { return n == 0 ? 1 : odd(n - 1); }
                    int odd(int n) { return n == 0 ? 0 : even(n - 1); }
                    void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = even(i) + odd(i) + even(a[i]);
                        }
                    }
                }""");

        // even is entered first from the kernel, so odd's call back to even closes the cycle
        assertEquals("even(n - 1)", assertOne("inspection.recursion").getText());
    }

    public void testDiamondCallGraphIsNotRecursive() {
        configure("""
                class Demo {
                    static int d(int x) { return x + 1; }
                    static int b(int x) { return d(x) * 2; }
                    static int c(int x) { return d(x) * 3; }
                    static int a(int x) { return b(x) + c(x); }
                    static void kernel(int[] arr) {
                        for (@Parallel int i = 0; i < arr.length; i++) {
                            arr[i] = a(i) + b(i) + d(i);
                        }
                    }
                }""");

        assertEmpty(problems("inspection.recursion"));
        assertEmpty(tornadoProblems());
    }

    public void testRecursionInHelperCalledFromTwoKernelsIsReportedOnce() {
        configure("""
                class Demo {
                    int f(int n) { return n <= 0 ? 0 : g(n - 1); }
                    int g(int n) { return f(n); }
                    void first(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = f(i); } }
                    void second(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = g(i); } }
                }""");

        // The two kernels enter the cycle at different methods, so each reports its own
        // closing call; the cycle is still reported once per kernel
        List<HighlightInfo> infos = problems("inspection.recursion");
        assertSize(2, infos);
        assertEquals(List.of("f(n)", "g(n - 1)"),
                infos.stream().map(HighlightInfo::getText).sorted().toList());
    }

    public void testThrowTryAndThrowsClause() {
        configure("""
                class Demo {
                    static void kernel(int[] a) throws Exception {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (a[i] < 0) throw new IllegalStateException();
                            try { a[i] = 1; } catch (RuntimeException e) { a[i] = 0; }
                        }
                    }
                }""");

        assertEquals("throw new IllegalStateException();", assertOne("inspection.traps.throw").getText());
        assertTrue(assertOne("inspection.traps.tryCatch").getText().startsWith("try {"));
        assertEquals("throws Exception", assertOne("inspection.traps.throws").getText());
    }

    public void testAssert() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            assert a[i] >= 0;
                        }
                    }
                }""");

        HighlightInfo info = assertOne("inspection.assert");
        assertEquals(HighlightSeverity.ERROR, info.getSeverity());
        assertEquals("assert a[i] >= 0;", info.getText());
    }

    public void testSystemAndNativeCalls() {
        configure("""
                class Demo {
                    static native int nat(int x);
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            System.out.println(i);
                            a[i] = nat(i);
                        }
                    }
                }""");

        HighlightInfo external = assertOne("inspection.external");
        assertEquals(HighlightSeverity.ERROR, external.getSeverity());
        assertEquals("System.out.println(i)", external.getText());
        assertEquals("nat(i)", assertOne("inspection.nativeCall").getText());
    }

    public void testNonVoidReturn() {
        configure("""
                class Demo {
                    static int kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; }
                        return 0;
                    }
                }""");

        HighlightInfo info = assertOne("inspection.nonVoidReturn");
        assertEquals(HighlightSeverity.WARNING, info.getSeverity());
        assertEquals("int", info.getText());
    }

    public void testTwoDimensionalKernelReportsEachProblemOnce() {
        configure("""
                class Demo {
                    static void kernel(int[] a, int n) {
                        for (@Parallel int i = 0; i < n; i++) {
                            for (@Parallel int j = 0; j < n; j++) {
                                String s = "x";
                                assert i >= 0;
                                if (j < 0) throw new IllegalStateException();
                                System.out.println(j);
                            }
                        }
                    }
                }""");

        assertOne("inspection.datatype");
        assertOne("inspection.assert");
        assertOne("inspection.traps.throw");
        assertOne("inspection.external");
    }

    public void testHelperSharedByTwoKernelsIsReportedOnce() {
        configure("""
                class Demo {
                    static int check(int x) { assert x >= 0; return x; }
                    static void first(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = check(i); } }
                    static void second(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = check(a[i]); } }
                }""");

        assertOne("inspection.assert");
    }

    public void testKernelContextKernelIsChecked() {
        configure("""
                class Demo {
                    static void kernel(KernelContext context, int[] a) {
                        int i = context.globalIdx;
                        String s = "x";
                        assert i >= 0;
                        System.out.println(a[i]);
                    }
                }""");

        assertOne("inspection.datatype");
        assertOne("inspection.assert");
        assertOne("inspection.external");
    }

    public void testTileContextKernelIsChecked() {
        addTileContext();
        configure("""
                import uk.ac.manchester.tornado.api.tile.TileContext;
                class Demo {
                    static void kernel(TileContext tc, int[] a) {
                        assert a.length > 0;
                    }
                }""");

        assertOne("inspection.assert");
    }

    public void testUnresolvableHelperIsReportedOncePerCallSite() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = undefined(i);
                        }
                    }
                }""");

        HighlightInfo info = assertOne("inspection.helper.unresolvable");
        assertEquals(HighlightSeverity.WEAK_WARNING, info.getSeverity());
        assertEquals("undefined(i)", info.getText());
    }

    private void configure(String body) {
        myFixture.configureByText("Demo.java", HEADER + body);
    }

    private HighlightInfo assertOne(String messageKey) {
        List<HighlightInfo> infos = problems(messageKey);
        assertSize(1, infos);
        return infos.get(0);
    }

    private List<HighlightInfo> problems(String messageKey) {
        String message = MessageBundle.message(messageKey);
        return myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null && info.getDescription().startsWith(message))
                .toList();
    }

    private List<HighlightInfo> tornadoProblems() {
        return myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null && info.getDescription().startsWith("TornadoVM:"))
                .toList();
    }
}
