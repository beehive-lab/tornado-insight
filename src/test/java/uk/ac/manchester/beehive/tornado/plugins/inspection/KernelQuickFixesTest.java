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
 * The QuickFixes of the kernel inspections: each is applied at the caret and the
 * resulting file compared with the expected text.
 */
public class KernelQuickFixesTest extends TornadoTestCase {

    private static final String HEADER = """
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        addSystemStubs();
        myFixture.enableInspections(new AssertInspection(), new SystemCallInspection());
    }

    public void testRemoveAssert() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            <caret>assert a[i] >= 0;
                            a[i] = 1;
                        }
                    }
                }""");

        apply("quickfix.removeAssert");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                    }
                }""");
    }

    public void testAnAssertThatIsABranchBodyBecomesAnEmptyBlock() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (i > 0) <caret>assert a[i] >= 0;
                            a[i] = 1;
                        }
                    }
                }""");

        apply("quickfix.removeAssert");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            if (i > 0) {
                            }
                            a[i] = 1;
                        }
                    }
                }""");
    }

    public void testRemoveAssertInASameFileHelper() {
        configure("""
                class Demo {
                    static int check(int x) {
                        <caret>assert x >= 0;
                        return x;
                    }
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) { a[i] = check(i); }
                    }
                }""");

        apply("quickfix.removeAssert");

        check("""
                class Demo {
                    static int check(int x) {
                        return x;
                    }
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) { a[i] = check(i); }
                    }
                }""");
    }

    public void testAnAssertInAnotherFilesHelperHasNoFix() {
        myFixture.addClass("""
                public class Checks {
                    public static int check(int x) { assert x >= 0; return x; }
                }""");
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) { a[i] = <caret>Checks.check(i); }
                    }
                }""");

        // The problem is shown on the call, which must not be removed
        assertNotEmpty(myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null
                        && info.getDescription().startsWith(MessageBundle.message("inspection.assert")))
                .toList());
        assertEmpty(fixes("quickfix.removeAssert"));
        assertEmpty(fixes("quickfix.removeCall"));
    }

    public void testRemoveSystemCall() {
        configure("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                            System.out.<caret>println(i);
                        }
                    }
                }""");

        apply("quickfix.removeCall");

        check("""
                class Demo {
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = 1;
                        }
                    }
                }""");
    }

    public void testRemoveNativeCall() {
        configure("""
                class Demo {
                    static native void log(int x);
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            <caret>log(i);
                        }
                    }
                }""");

        apply("quickfix.removeCall");

        check("""
                class Demo {
                    static native void log(int x);
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                        }
                    }
                }""");
    }

    public void testACallWhoseValueIsUsedHasNoFix() {
        configure("""
                class Demo {
                    static native int read(int x);
                    static void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[i] = <caret>read(i);
                        }
                    }
                }""");

        assertNotEmpty(myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null
                        && info.getDescription().startsWith(MessageBundle.message("inspection.nativeCall")))
                .toList());
        assertEmpty(fixes("quickfix.removeCall"));
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
