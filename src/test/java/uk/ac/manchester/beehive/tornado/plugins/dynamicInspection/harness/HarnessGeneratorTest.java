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

import org.junit.Test;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Field;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.TaskGraphDeclaration;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HarnessGeneratorTest {

    static final String KERNEL = """
            public static void vadd(FloatArray a, FloatArray b, FloatArray c, int size) {
                    for (@Parallel int i = 0; i < size; i++) { c.set(i, a.get(i) + b.get(i)); }
                }""";

    static HarnessModel model(List<Parameter> parameters, TaskGraphDeclaration taskGraph) {
        return new HarnessModel("vaddAb12C", "vadd", TornadoKernels.Kind.PARALLEL_LOOP,
                List.of("import uk.ac.manchester.tornado.api.types.arrays.FloatArray;"),
                List.of(new Field("private static final", "int", "SIZE", "64")),
                KERNEL, List.of("static float twice(float x) { return 2 * x; }"), parameters, taskGraph);
    }

    static final List<Parameter> VADD_PARAMETERS = List.of(
            new Parameter("a", "FloatArray", false, null),
            new Parameter("b", "FloatArray", false, null),
            new Parameter("c", "FloatArray", false, null),
            new Parameter("size", "int", false, "SIZE"));

    @Test
    public void syntheticTaskGraph() throws Exception {
        String source = HarnessGenerator.generate(model(VADD_PARAMETERS, null), 16, new Random(1));

        assertTrue(source, source.startsWith(HarnessGenerator.DEFAULT_IMPORTS
                + "import uk.ac.manchester.tornado.api.types.arrays.FloatArray;\n\npublic class vaddAb12C {\n"
                + "private static final int SIZE = 64;\n" + KERNEL
                + "static float twice(float x) { return 2 * x; }\n"));
        assertTrue(source, source.matches("(?s).*\t\tFloatArray a= new FloatArray\\(16\\);a\\.init\\([0-9.]+f\\);\n.*"));
        assertTrue(source, source.contains("\t\tint size=SIZE;\n"));
        assertTrue(source, source.contains("""
                TaskGraph taskGraph = new TaskGraph("insightTaskGraphName")\s
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, a, b, c)
                .task("insightTaskName", vaddAb12C::vadd, a, b, c, size)\s
                .transferToHost(DataTransferMode.EVERY_EXECUTION, a, b, c);
                ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
                try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
                executionPlan.withPreCompilation();
                """));
        assertTrue(source.endsWith("    }}"));
    }

    @Test
    public void reusedTaskGraph() throws Exception {
        String declaration = """
                TaskGraph graph = new TaskGraph("s0")
                        .transferToDevice(DataTransferMode.FIRST_EXECUTION, a, b)
                        .task("t0", vaddAb12C::vadd, a, b, c, size)
                        .transferToHost(DataTransferMode.EVERY_EXECUTION, c);""";
        String source = HarnessGenerator.generate(
                model(VADD_PARAMETERS, new TaskGraphDeclaration(declaration, "graph", "s0", "t0")), 16, new Random(1));

        assertTrue(source, source.contains(declaration + "\nImmutableTaskGraph immutableTaskGraph = graph.snapshot();\n"));
        assertEquals(-1, source.indexOf("insightTaskGraphName"));
    }

    @Test
    public void fieldDeclarations() {
        assertEquals("int N", HarnessGenerator.fieldDeclaration(new Field("", "int", "N", null)));
        assertEquals("static final float[] W = {1f, 2f}",
                HarnessGenerator.fieldDeclaration(new Field("static final", "float[]", "W", "{1f, 2f}")));
    }

    private static HarnessModel contextModel(TornadoKernels.Kind kind, TaskGraphDeclaration taskGraph) {
        String context = kind == TornadoKernels.Kind.TILE_CONTEXT
                ? "uk.ac.manchester.tornado.api.tile.TileContext" : "uk.ac.manchester.tornado.api.KernelContext";
        return new HarnessModel("addAb12C", "add", kind, List.of(), List.of(),
                "public static void add(Context context, FloatArray a) { }\n", List.of(),
                List.of(new Parameter("context", context, false, null), new Parameter("a", "FloatArray", false, null)),
                taskGraph);
    }

    @Test
    public void kernelContextTaskGetsAWorkerGridOverTheTestData() throws Exception {
        String source = HarnessGenerator.generate(contextModel(TornadoKernels.Kind.KERNEL_CONTEXT, null), 128, new Random(1));

        assertTrue(source, source.contains("""
                ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();
                uk.ac.manchester.tornado.api.WorkerGrid1D insightWorkerGrid = new uk.ac.manchester.tornado.api.WorkerGrid1D(128);
                uk.ac.manchester.tornado.api.GridScheduler insightGridScheduler = new uk.ac.manchester.tornado.api.GridScheduler("insightTaskGraphName.insightTaskName", insightWorkerGrid);
                try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
                executionPlan.withGridScheduler(insightGridScheduler).withPreCompilation();
                """));
    }

    @Test
    public void tileContextTaskLaunchesOneTileBlock() throws Exception {
        String declaration = "TaskGraph tile = new TaskGraph(\"tile\").task(\"add\", addAb12C::add, context, a);";
        String source = HarnessGenerator.generate(contextModel(TornadoKernels.Kind.TILE_CONTEXT,
                new TaskGraphDeclaration(declaration, "tile", "tile", "add")), 128, new Random(1));

        assertTrue(source, source.contains("new uk.ac.manchester.tornado.api.WorkerGrid1D(1);"));
        assertTrue(source, source.contains("new uk.ac.manchester.tornado.api.GridScheduler(\"tile.add\", insightWorkerGrid);"));
        assertTrue(source, source.contains("executionPlan.withGridScheduler(insightGridScheduler).withPreCompilation();"));
    }

    @Test
    public void loopKernelsAndUnnamedTasksGetNoGridScheduler() throws Exception {
        assertEquals(-1, HarnessGenerator.generate(model(VADD_PARAMETERS, null), 16, new Random(1)).indexOf("GridScheduler"));
        assertEquals("", HarnessGenerator.gridScheduler(contextModel(TornadoKernels.Kind.KERNEL_CONTEXT, null), null, "t0", 16));
    }
}
