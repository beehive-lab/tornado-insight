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

package uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow.TornadoTaskService.TaskEntry;
import uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow.TornadoTaskService.TaskSnapshot;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoTWTask;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TornadoTaskServiceTest extends TornadoTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    public void testSnapshotsOfTwoFilesHoldTheirOwnTasks() {
        PsiFile first = myFixture.addFileToProject("First.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class First {
                    static void add(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] += 1; } }
                }""");
        PsiFile second = myFixture.addFileToProject("Second.java", """
                import uk.ac.manchester.tornado.api.KernelContext;
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Second {
                    static void mul(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] *= 2; } }
                    static void ctx(KernelContext context, int[] a) { }
                }""");

        TaskSnapshot firstSnapshot = TornadoTaskService.computeSnapshot(first);
        TaskSnapshot secondSnapshot = TornadoTaskService.computeSnapshot(second);

        assertEquals(Set.of("add"), methodNames(firstSnapshot));
        assertEquals(Set.of("mul", "ctx"), methodNames(secondSnapshot));
        assertEquals(first, firstSnapshot.file().getElement());
        assertEquals(second, secondSnapshot.file().getElement());
        for (TaskEntry entry : secondSnapshot.tasks()) {
            assertEquals(entry.displayName(), entry.toString());
            assertEquals(TornadoTWTask.psiMethodFormat(entry.resolve()), entry.displayName());
        }
    }

    public void testEqualDisplayNamesMapToDistinctMethods() {
        PsiFile file = myFixture.configureByText("Outer.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Outer {
                    static class A {
                        static void scale(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] *= 2; } }
                    }
                    static class B {
                        static void scale(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] *= 3; } }
                    }
                }""");

        List<TaskEntry> tasks = TornadoTaskService.computeSnapshot(file).tasks();

        assertSize(2, tasks);
        assertEquals(tasks.get(0).displayName(), tasks.get(1).displayName());
        Set<String> owners = tasks.stream()
                .map(entry -> entry.resolve().getContainingClass().getName())
                .collect(Collectors.toSet());
        assertEquals(Set.of("A", "B"), owners);
    }

    public void testDeletedTaskResolvesToNull() {
        PsiFile file = myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Demo {
                    static void add(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] += 1; } }
                }""");
        TaskEntry entry = TornadoTaskService.computeSnapshot(file).tasks().get(0);

        PsiMethod method = entry.resolve();
        WriteCommandAction.runWriteCommandAction(getProject(), method::delete);

        assertNull(entry.resolve());
    }

    public void testKernelsWithBlockingProblemsAreExcludedWithoutRunningInspections() {
        addSystemStubs();
        // No inspection is enabled: validity comes from KernelValidator alone
        PsiFile file = myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.KernelContext;
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Demo {
                    static int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }
                    static int checked(int x) { if (x < 0) throw new IllegalArgumentException(); return x; }

                    static void clean(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] += 1; } }
                    static void throwing(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { if (a[i] < 0) throw new IllegalStateException(); } }
                    static void printing(KernelContext context, int[] a) { System.out.println(a[context.globalIdx]); }
                    static void recursive(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = fact(i); } }
                    static void viaHelper(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = checked(a[i]); } }
                    static int nonVoid(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; } return 0; }
                    static void unanalysable(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = undefined(i); } }
                }""");

        assertEquals(Set.of("clean", "unanalysable"), methodNames(TornadoTaskService.computeSnapshot(file)));
    }

    private static Set<String> methodNames(TaskSnapshot snapshot) {
        return snapshot.tasks().stream().map(entry -> entry.resolve().getName()).collect(Collectors.toSet());
    }
}
