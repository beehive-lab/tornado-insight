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

import com.intellij.codeInsight.intention.IntentionAction;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.List;

/**
 * The QuickFixes of the exception and return-type inspections: each is applied at the caret
 * and the resulting file compared with the expected text.
 */
public class TrapQuickFixesTest extends TornadoTestCase {

    private static final String HEADER = """
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        myFixture.enableInspections(new ThrowInspection(), new NonVoidReturnInspection());
    }

    public void testRemoveThrow() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (a[i] < 0) <caret>throw new IllegalArgumentException();
                            a[i] = 1;
                        }
                    }
                }""");

        apply("quickfix.removeThrow");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (a[i] < 0) {
                            }
                            a[i] = 1;
                        }
                    }
                }""");
    }

    public void testNoRemoveThrowInAMethodThatReturnsAValue() {
        configure("""
                class Demo {
                    static int kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                        <caret>throw new IllegalStateException();
                    }
                }""");

        assertEmpty(fixes("quickfix.removeThrow"));
    }

    public void testUnwrapTryKeepsTryAndFinallyStatements() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            <caret>try {
                                a[i] = 1;
                            } catch (RuntimeException e) {
                                a[i] = -1;
                            } finally {
                                a[i] += 1;
                            }
                        }
                    }
                }""");

        apply("quickfix.unwrapTry");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                            a[i] += 1;
                        }
                    }
                }""");
    }

    public void testUnwrapTryKeepsABlockWhenANameWouldClash() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            <caret>try {
                                int v = i * 2;
                                a[i] = v;
                            } catch (RuntimeException e) {
                            }
                            int v = 3;
                            a[i] += v;
                        }
                    }
                }""");

        apply("quickfix.unwrapTry");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            {
                                int v = i * 2;
                                a[i] = v;
                            }
                            int v = 3;
                            a[i] += v;
                        }
                    }
                }""");
    }

    public void testNoUnwrapForTryWithResources() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            <caret>try (Res r = new Res()) {
                                a[i] = 1;
                            }
                        }
                    }
                    static class Res implements AutoCloseable { public void close() { } }
                }""");

        assertEmpty(fixes("quickfix.unwrapTry"));
    }

    public void testRemoveThrowsClauseIsOfferedOnce() {
        configure("""
                class Demo {
                    static void kernel(int[] a) <caret>throws Exception, Error {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                    }
                }""");

        apply("quickfix.removeThrows");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                    }
                }""");
    }

    public void testMakeVoid() {
        configure("""
                class Demo {
                    static <caret>int kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (a[i] < 0) {
                                return -1;
                            }
                            a[i] = 1;
                        }
                        return a[0];
                    }
                }""");

        apply("quickfix.makeVoid");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (a[i] < 0) {
                                return;
                            }
                            a[i] = 1;
                        }
                    }
                }""");
    }

    public void testMakeVoidKeepsAReturnedCall() {
        configure("""
                class Demo {
                    static <caret>int kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                        return fill(a);
                    }
                    static int fill(int[] a) { a[0] = 2; return 2; }
                }""");

        apply("quickfix.makeVoid");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                        fill(a);
                    }
                    static int fill(int[] a) { a[0] = 2; return 2; }
                }""");
    }

    private void configure(String body) {
        myFixture.configureByText("Demo.java", HEADER + body);
    }

    private List<IntentionAction> fixes(String nameKey) {
        String name = MessageBundle.message(nameKey);
        return myFixture.getAvailableIntentions().stream().filter(action -> action.getText().equals(name)).toList();
    }

    private void apply(String nameKey) {
        List<IntentionAction> fixes = fixes(nameKey);
        assertSize(1, fixes);
        myFixture.launchAction(fixes.get(0));
    }

    private void check(String body) {
        myFixture.checkResult(HEADER + body);
    }
}
