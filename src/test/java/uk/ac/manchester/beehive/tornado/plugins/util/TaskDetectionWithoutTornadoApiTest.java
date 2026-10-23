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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.psi.PsiFile;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.inspection.DataTypeInspection;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTaskService.TaskEntry;

import java.util.List;

/**
 * A module whose classpath lacks the TornadoVM API: kernels are still listed, and
 * their unresolved TornadoVM types are not reported as unsupported data types.
 */
public class TaskDetectionWithoutTornadoApiTest extends TornadoTestCase {

    private static final String VECTOR_ADD = """
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            import uk.ac.manchester.tornado.api.types.arrays.IntArray;

            class VectorAddInt {
                private static void vectorAdd(IntArray a, IntArray b, IntArray c) {
                    for (@Parallel int i = 0; i < c.getSize(); i++) {
                        c.set(i, a.get(i) + b.get(i));
                    }
                }
            }""";

    public void testKernelWithUnresolvedTornadoTypesIsListed() {
        PsiFile file = myFixture.configureByText("VectorAddInt.java", VECTOR_ADD);

        List<String> tasks = TornadoTaskService.computeSnapshot(file).tasks().stream().map(TaskEntry::displayName).toList();

        assertEquals(List.of("vectorAdd(IntArray, IntArray, IntArray): void"), tasks);
    }

    public void testUnresolvedTornadoTypesAreNotReportedAsUnsupported() {
        myFixture.configureByText("VectorAddInt.java", VECTOR_ADD);
        myFixture.enableInspections(new DataTypeInspection());

        List<HighlightInfo> unsupported = myFixture.doHighlighting().stream()
                .filter(info -> info.getDescription() != null && info.getDescription().contains(MessageBundle.message("inspection.datatype")))
                .toList();

        assertEmpty(unsupported);
    }
}
