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

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoTWTask;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TaskDetectionTest extends TornadoTestCase {

    public void testDetectsParallelKernelContextAndTileContextTasks() {
        addTornadoApi();
        addTileContext();
        PsiFile file = myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.KernelContext;
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.tile.TileContext;

                class Demo {
                    static void parallel(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; } }
                    static void kernel(KernelContext context, int[] a) { }
                    static void tile(TileContext tc, int[] a) { }
                    static void helper(int[] a) { }
                }""");

        List<PsiMethod> tasks = TornadoTWTask.findSuitableMethods(file);
        Set<String> names = tasks.stream().map(PsiMethod::getName).collect(Collectors.toSet());

        assertEquals(Set.of("parallel", "kernel", "tile"), names);
    }
}
