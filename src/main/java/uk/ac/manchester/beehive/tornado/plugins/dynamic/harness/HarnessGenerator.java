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

package uk.ac.manchester.beehive.tornado.plugins.dynamic.harness;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.Field;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.TaskGraphDeclaration;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.Random;

/**
 * Generates the Java source of a test harness from a {@link HarnessModel}: the
 * kernel, its helpers and the fields they use, plus a {@code main} that allocates
 * test data for every kernel argument, builds the TaskGraph and pre-compiles it
 * with TornadoVM. Pure: it reads nothing but its arguments.
 */
public final class HarnessGenerator {

    static final String DEFAULT_IMPORTS = """
            import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
            import uk.ac.manchester.tornado.api.TaskGraph;
            import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            import uk.ac.manchester.tornado.api.annotations.Reduce;
            import uk.ac.manchester.tornado.api.enums.DataTransferMode;
            import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
            import uk.ac.manchester.tornado.api.types.HalfFloat;
            """;

    static final String SYNTHETIC_GRAPH = "insightTaskGraphName";
    static final String SYNTHETIC_TASK = "insightTaskName";
    static final String WORKER_GRID = "insightWorkerGrid";
    static final String GRID_SCHEDULER = "insightGridScheduler";

    private HarnessGenerator() {
    }

    /**
     * Generates the harness source.
     *
     * @param model         the harness to generate
     * @param parameterSize the size of the generated test data (array lengths, sizes)
     * @param random        the source of the test values
     * @throws UnsupportedParameterTypeException if no test data can be generated for a parameter
     */
    public static @NotNull String generate(@NotNull HarnessModel model, int parameterSize, @NotNull Random random)
            throws UnsupportedParameterTypeException {
        ParameterInitializer initializer = new ParameterInitializer(parameterSize, random);
        StringBuilder variableInit = new StringBuilder();
        for (Parameter parameter : model.parameters()) {
            variableInit.append(initializer.declaration(parameter)).append("\n");
        }

        StringBuilder source = new StringBuilder();
        source.append(DEFAULT_IMPORTS);
        for (String importStatement : model.imports()) {
            source.append(importStatement).append("\n");
        }
        source.append("\n");
        source.append("public class ").append(model.className()).append(" {\n");
        for (Field field : model.fields()) {
            source.append(fieldDeclaration(field)).append(";\n");
        }
        source.append(model.kernelSource());
        for (String helper : model.helperSources()) {
            source.append(helper).append("\n");
        }
        source.append(mainMethod(model, variableInit.toString(), parameterSize));
        source.append("}");
        return source.toString();
    }

    static @NotNull String fieldDeclaration(@NotNull Field field) {
        StringBuilder declaration = new StringBuilder();
        if (!field.modifiers().isEmpty()) {
            declaration.append(field.modifiers()).append(" ");
        }
        declaration.append(field.type()).append(" ").append(field.name());
        if (field.initializer() != null) {
            declaration.append(" = ").append(field.initializer());
        }
        return declaration.toString();
    }

    private static String mainMethod(HarnessModel model, String variableInit, int parameterSize) {
        String methodWithClass = model.className() + "::" + model.kernelName();
        TaskGraphDeclaration taskGraph = model.taskGraph();
        if (taskGraph != null) {
            String grid = gridScheduler(model, taskGraph.graphName(), taskGraph.taskName(), parameterSize);
            return """
                    \n\tpublic static void main(String[] args) throws TornadoExecutionPlanException {
                    %s
                    %s
                    ImmutableTaskGraph immutableTaskGraph = %s.snapshot();
                    %stry (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
                        executionPlan%s.withPreCompilation();
                    }
                    }
                    """.formatted(variableInit, taskGraph.declaration(), taskGraph.variableName(),
                    grid, grid.isEmpty() ? "" : ".withGridScheduler(" + GRID_SCHEDULER + ")");
        }

        // Build a TaskGraph with a single task for the kernel
        StringBuilder taskParameters = new StringBuilder();
        StringBuilder taskGraphParameters = new StringBuilder();
        for (Parameter p : model.parameters()) {
            taskParameters.append(", ").append(p.name());
            if (isTransferred(p)) {
                taskGraphParameters.append(", ").append(p.name());
            }
        }
        String grid = gridScheduler(model, SYNTHETIC_GRAPH, SYNTHETIC_TASK, parameterSize);
        return "\n\tpublic static void main(String[] args) throws TornadoExecutionPlanException {\n" + //
                "\n" + //
                variableInit + //
                "TaskGraph taskGraph = new TaskGraph(\"" + SYNTHETIC_GRAPH + "\") \n" + //
                ".transferToDevice(DataTransferMode.EVERY_EXECUTION" + taskGraphParameters + ")\n" + //
                ".task(\"" + SYNTHETIC_TASK + "\", " + methodWithClass + taskParameters + ") \n" + //
                ".transferToHost(DataTransferMode.EVERY_EXECUTION" + taskGraphParameters + ");\n" + //
                "ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();\n" + //
                grid + //
                "try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {\n" + //
                "executionPlan" + (grid.isEmpty() ? "" : ".withGridScheduler(" + GRID_SCHEDULER + ")") + ".withPreCompilation();\n" + //
                "        }\n" + //
                "    }"; //
    }

    /**
     * KernelContext and TileContext kernels have no {@code @Parallel} loop to derive
     * the iteration space from, so they need a worker grid. A KernelContext kernel
     * gets one thread per element of the test data. The worker grid of a TileContext
     * kernel counts tile blocks and its local work is left to the tile compiler (as in
     * TornadoVM's TileVectorAdd example); the harness cannot know the tile size, so it
     * launches a single tile block, which stays within the test data.
     *
     * @return the statements declaring {@link #GRID_SCHEDULER}, or an empty string
     *         when the kernel needs none or the task name is unknown
     */
    static @NotNull String gridScheduler(@NotNull HarnessModel model, @Nullable String graphName,
                                         @Nullable String taskName, int parameterSize) {
        TornadoKernels.Kind kind = model.kind();
        if (kind != TornadoKernels.Kind.KERNEL_CONTEXT && kind != TornadoKernels.Kind.TILE_CONTEXT) {
            return "";
        }
        if (graphName == null || taskName == null) {
            return "";
        }
        int threads = kind == TornadoKernels.Kind.TILE_CONTEXT ? 1 : parameterSize;
        return "uk.ac.manchester.tornado.api.WorkerGrid1D " + WORKER_GRID
                + " = new uk.ac.manchester.tornado.api.WorkerGrid1D(" + threads + ");\n"
                + "uk.ac.manchester.tornado.api.GridScheduler " + GRID_SCHEDULER
                + " = new uk.ac.manchester.tornado.api.GridScheduler(\"" + graphName + "." + taskName + "\", "
                + WORKER_GRID + ");\n";
    }

    // Scalars are passed by value; everything else is transferred to and from the device
    private static boolean isTransferred(Parameter p) {
        return switch (p.type()) {
            case "int", "long", "short", "byte", "char", "boolean", "float", "double" -> false;
            default -> true;
        };
    }
}
