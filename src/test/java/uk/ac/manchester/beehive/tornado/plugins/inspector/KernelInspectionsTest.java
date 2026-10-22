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
                new NonVoidReturnInspection(), new StaticTaskGraphInspection());
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

        List<HighlightInfo> infos = problems("inspection.recursion");
        assertFalse(infos.isEmpty());
        for (HighlightInfo info : infos) {
            assertEquals(HighlightSeverity.ERROR, info.getSeverity());
            assertTrue(info.getText().startsWith("static int fact(int n)"));
        }
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
