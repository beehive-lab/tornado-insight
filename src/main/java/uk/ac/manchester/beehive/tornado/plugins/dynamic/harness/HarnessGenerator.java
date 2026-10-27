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
import java.util.regex.Pattern;

/**
 * Generates the Java source of a test harness from a {@link HarnessModel}: the
 * kernel, its helpers and the fields they use, plus a {@code main} that allocates
 * test data for every kernel argument, builds the TaskGraph and pre-compiles it
 * with TornadoVM. Pure: it reads nothing but its arguments.
 */
public final class HarnessGenerator {

    private static final String API = "uk.ac.manchester.tornado.api.";

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
        HarnessImports imports = new HarnessImports(model.imports());
        requireApiImports(model, imports);

        ParameterInitializer initializer = new ParameterInitializer(parameterSize, random);
        StringBuilder variableInit = new StringBuilder();
        for (Parameter parameter : model.parameters()) {
            Parameter simplified = new Parameter(parameter.name(), imports.use(parameter.type()),
                    parameter.localMemorySize(), parameter.intOverride());
            variableInit.append(initializer.declaration(simplified)).append("\n");
        }
        if (initializer.usesHalfFloat()) {
            imports.require(API + "types.HalfFloat");
        }

        StringBuilder body = new StringBuilder();
        body.append("public class ").append(model.className()).append(" {\n");
        for (Field field : model.fields()) {
            Field simplified = new Field(field.modifiers(), imports.use(field.type()), field.name(), field.initializer());
            body.append(fieldDeclaration(simplified)).append(";\n");
        }
        body.append(model.kernelSource());
        for (String helper : model.helperSources()) {
            body.append(helper).append("\n");
        }
        body.append(mainMethod(model, variableInit.toString(), parameterSize, imports));
        body.append("}\n");
        return imports.source() + "\n" + body;
    }

    /**
     * Imports the TornadoVM API types the harness uses: those of {@code main}, and
     * the loop annotations if the copied code has them. The user's own imports
     * usually cover the annotations already; the duplicates are dropped.
     */
    private static void requireApiImports(HarnessModel model, HarnessImports imports) {
        imports.require(API + "ImmutableTaskGraph");
        imports.require(API + "TornadoExecutionPlan");
        imports.require(API + "exceptions.TornadoExecutionPlanException");
        TaskGraphDeclaration taskGraph = model.taskGraph();
        if (taskGraph == null || mentions(taskGraph.declaration(), "TaskGraph")) {
            imports.require(API + "TaskGraph");
        }
        if (taskGraph == null || mentions(taskGraph.declaration(), "DataTransferMode")) {
            imports.require(API + "enums.DataTransferMode");
        }
        String code = model.kernelSource() + String.join("\n", model.helperSources());
        if (mentions(code, "@Parallel")) {
            imports.require(API + "annotations.Parallel");
        }
        if (mentions(code, "@Reduce")) {
            imports.require(API + "annotations.Reduce");
        }
    }

    // Whether code uses the simple name unqualified, so it needs an import
    private static boolean mentions(String code, String simpleName) {
        return Pattern.compile("(?<![\\w.])" + Pattern.quote(simpleName) + "\\b").matcher(code).find();
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

    private static String mainMethod(HarnessModel model, String variableInit, int parameterSize,
                                     HarnessImports imports) {
        String methodWithClass = model.className() + "::" + model.kernelName();
        TaskGraphDeclaration taskGraph = model.taskGraph();
        if (taskGraph != null) {
            String grid = gridScheduler(model, taskGraph.graphName(), taskGraph.taskName(), parameterSize, imports);
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
        String grid = gridScheduler(model, SYNTHETIC_GRAPH, SYNTHETIC_TASK, parameterSize, imports);
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
                                         @Nullable String taskName, int parameterSize,
                                         @NotNull HarnessImports imports) {
        TornadoKernels.Kind kind = model.kind();
        if (kind != TornadoKernels.Kind.KERNEL_CONTEXT && kind != TornadoKernels.Kind.TILE_CONTEXT) {
            return "";
        }
        if (graphName == null || taskName == null) {
            return "";
        }
        int threads = kind == TornadoKernels.Kind.TILE_CONTEXT ? 1 : parameterSize;
        String workerGrid = imports.use(API + "WorkerGrid1D");
        String gridScheduler = imports.use(API + "GridScheduler");
        return workerGrid + " " + WORKER_GRID + " = new " + workerGrid + "(" + threads + ");\n"
                + gridScheduler + " " + GRID_SCHEDULER + " = new " + gridScheduler + "(\""
                + graphName + "." + taskName + "\", " + WORKER_GRID + ");\n";
    }

    // Scalars are passed by value; everything else is transferred to and from the device
    private static boolean isTransferred(Parameter p) {
        return switch (p.type()) {
            case "int", "long", "short", "byte", "char", "boolean", "float", "double" -> false;
            default -> true;
        };
    }
}
