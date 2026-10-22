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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.inspector.TornadoKernels;

import java.util.List;

public class HarnessModelBuilderTest extends TornadoTestCase {

    private static final String INT_ARRAY = "uk.ac.manchester.tornado.api.types.arrays.IntArray";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    private HarnessModel build(String fileName, String text, String kernelName) {
        PsiFile file = myFixture.configureByText(fileName, text);
        PsiMethod kernel = TornadoKernels.findKernels(file).stream()
                .filter(m -> m.getName().equals(kernelName))
                .findFirst().orElseThrow();
        return HarnessModelBuilder.build(kernel, kernelName + "Ab12C");
    }

    public void testParameterTypesAreCanonical() {
        HarnessModel model = build("Kernels.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.types.arrays.IntArray;

                class Kernels {
                    static void copy(uk.ac.manchester.tornado.api.types.arrays.IntArray a, final IntArray b, int n) {
                        for (@Parallel int i = 0; i < n; i++) { b.set(i, a.get(i)); }
                    }
                }""", "copy");

        assertEquals(List.of(
                new Parameter("a", INT_ARRAY, false, null),
                new Parameter("b", INT_ARRAY, false, null),
                new Parameter("n", "int", false, null)), model.parameters());
        assertEquals(TornadoKernels.Kind.PARALLEL_LOOP, model.kind());
    }

    private static final String KERNELS = """
            import uk.ac.manchester.tornado.api.TaskGraph;
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            import uk.ac.manchester.tornado.api.enums.DataTransferMode;
            import uk.ac.manchester.tornado.api.types.arrays.IntArray;

            class K {
                static void a(IntArray x) { for (@Parallel int i = 0; i < x.getSize(); i++) { x.set(i, 1); } }
                static void b(IntArray y) { for (@Parallel int i = 0; i < y.getSize(); i++) { y.set(i, 2); } }
                static void scale(IntArray data, int factor) {
                    for (@Parallel int i = 0; i < data.getSize(); i++) { data.set(i, data.get(i) * factor); }
                }
                void run(IntArray x, IntArray y, IntArray data, int factor) {
                    %s
                }
            }""";

    public void testSingleTaskGraphIsReusedWithOnlyTheKernelReferenceRewritten() {
        HarnessModel model = build("K.java", KERNELS.formatted("""
                TaskGraph graph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, data)
                            .task("t0", K::scale, data, factor)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, data);"""), "scale");

        assertNotNull(model.taskGraph());
        assertEquals("""
                TaskGraph graph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, data)
                            .task("t0", scaleAb12C::scale, data, factor)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, data);""", model.taskGraph().declaration());
        assertEquals("graph", model.taskGraph().variableName());
        assertEquals("s0", model.taskGraph().graphName());
        assertEquals("t0", model.taskGraph().taskName());
        assertEquals(List.of(new Parameter("data", INT_ARRAY, false, null), new Parameter("factor", "int", false, null)),
                model.parameters());
    }

    public void testGraphWithOtherTasksFallsBackToASyntheticGraph() {
        String graph = """
                TaskGraph graph = new TaskGraph("s0")
                            .task("t0", K::a, x)
                            .task("t1", K::b, y);""";
        HarnessModel a = build("K.java", KERNELS.formatted(graph), "a");
        HarnessModel b = build("K.java", KERNELS.formatted(graph), "b");

        // Rewriting every .task(...) would have made both tasks call the harness kernel
        assertNull(a.taskGraph());
        assertNull(b.taskGraph());
        assertEquals(List.of(new Parameter("x", INT_ARRAY, false, null)), a.parameters());
        assertEquals(List.of(new Parameter("y", INT_ARRAY, false, null)), b.parameters());
    }

    public void testGraphPassingAnExpressionFallsBackToASyntheticGraph() {
        HarnessModel model = build("K.java", KERNELS.formatted("""
                TaskGraph graph = new TaskGraph("s0").task("t0", K::scale, data, factor * 2);"""), "scale");

        assertNull(model.taskGraph());
        assertEquals(List.of(new Parameter("data", INT_ARRAY, false, null), new Parameter("factor", "int", false, null)),
                model.parameters());
    }

    public void testTaskOfASameNamedKernelInAnotherClassIsNotReused() {
        myFixture.addClass("""
                package other;
                public class Other {
                    public static void scale(uk.ac.manchester.tornado.api.types.arrays.IntArray data, int factor) { }
                }""");
        HarnessModel model = build("K.java", KERNELS.formatted("""
                TaskGraph graph = new TaskGraph("s0").task("t0", other.Other::scale, data, factor);"""), "scale");

        assertNull(model.taskGraph());
    }

    public void testGraphUsingOtherLocalsFallsBackToASyntheticGraph() {
        HarnessModel model = build("K.java", KERNELS.formatted("""
                TaskGraph graph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, data, y)
                            .task("t0", K::scale, data, factor);"""), "scale");

        assertNull(model.taskGraph());
    }
}
