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

package uk.ac.manchester.beehive.tornado.plugins.dynamic;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiFile;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.CodeGenerator.Batch;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.CodeGenerator.Harness;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTaskService;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTaskService.TaskEntry;

import java.util.List;

public class CodeGeneratorTest extends TornadoTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    private static final String VECTOR_ADD = """
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            import uk.ac.manchester.tornado.api.types.arrays.IntArray;

            class VectorAdd {
                static void add(IntArray a, IntArray b, IntArray c) {
                    for (@Parallel int i = 0; i < c.getSize(); i++) { c.set(i, a.get(i) + b.get(i)); }
                }
                static void twice(IntArray a) {
                    for (@Parallel int i = 0; i < a.getSize(); i++) { a.set(i, a.get(i) * 2); }
                }
            }""";

    public void testHarnessesArePlainDataNamedAfterTheKernel() throws Exception {
        PsiFile file = myFixture.configureByText("VectorAdd.java", VECTOR_ADD);
        List<TaskEntry> tasks = TornadoTaskService.computeSnapshot(file).tasks();
        assertEquals(2, tasks.size());

        Batch batch = CodeGenerator.collect(tasks);

        assertEmpty(batch.staleTasks());
        assertEquals(2, batch.tasks().size());
        Harness add = CodeGenerator.render(batch.tasks().get(0), 16);
        assertEquals("add(IntArray, IntArray, IntArray): void", add.displayName());
        assertTrue(add.className(), add.className().matches("add[A-Za-z0-9]{5}"));
        assertTrue(add.source(), add.source().contains("public class " + add.className() + " {"));
        assertTrue(add.source(), add.source().contains(add.className() + "::add"));
        assertTrue(add.source(), add.source().contains("import uk.ac.manchester.tornado.api.types.arrays.IntArray;"));
        assertTrue(batch.tasks().get(1).model().className().startsWith("twice"));
    }

    public void testDeletedTaskIsReportedAsStale() {
        PsiFile file = myFixture.configureByText("VectorAdd.java", VECTOR_ADD);
        List<TaskEntry> tasks = TornadoTaskService.computeSnapshot(file).tasks();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> tasks.get(1).resolve().delete());

        Batch batch = CodeGenerator.collect(tasks);

        assertEquals(List.of("'twice(IntArray): void'"), batch.staleTasks());
        assertEquals(1, batch.tasks().size());
        assertEquals("add(IntArray, IntArray, IntArray): void", batch.tasks().get(0).displayName());
    }
}
