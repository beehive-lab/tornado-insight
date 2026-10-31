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
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates the harness that verifies a candidate kernel against the method it replaces. The
 * harness fills every parameter with synthetic test data, runs the original method in Java on a
 * copy of the data and the kernel with TornadoVM on the data itself, and compares every array and
 * the returned value. With a run count it then times both.
 * <p>
 * The harness takes five arguments: the number of elements per array, the warm-up runs, the timed
 * runs (0 to only verify), {@code java} or {@code kernel} to time the original method as well as the
 * kernel or the kernel only, and the tag that its findings carry. Timing stops after about 10 seconds once
 * there are 3 runs, and runs of over a second need no warm-up. Array lengths and {@code int} parameters are chosen so that the
 * original method runs: first one dimension ({@code n} elements, {@code int}s are {@code n}), then
 * two ({@code side}² elements, {@code int}s are {@code side}), then three. When it only verifies, it
 * checks two data sets: values from 0.5 to 1.5 on that shape, then values from -1 to 1 with zeros
 * on an odd side, so that the kernel also runs on a grid that is no multiple of a work-group size.
 * The profiler must report time for the kernel on the device: a kernel TornadoVM ran in Java
 * instead (a bailout) is not verified. The harness prints its findings on lines starting with
 * {@value #PREFIX} and the tag it was given, then a space:
 * </p>
 * <ul>
 *   <li>{@code data <set>}: the findings of data set 1 or 2 follow;</li>
 *   <li>{@code shape <dimensions> <side>}: the data the original method ran on;</li>
 *   <li>{@code original-failed <exception>}: the original method fails on every shape of set 1;</li>
 *   <li>{@code data-skipped <exception>}: it fails on every shape of set 2, which is not checked;</li>
 *   <li>{@code kernel-failed <exception>}: TornadoVM could not compile or run the kernel;</li>
 *   <li>{@code not-on-device}: the profiler reports no time for the kernel on the device;</li>
 *   <li>{@code mismatch <description>}: an output differs; then {@code different};</li>
 *   <li>{@code equal}: every output matches;</li>
 *   <li>{@code timing-kernel}: the original method is timed (if it is), the kernel's timing starts;</li>
 *   <li>{@code time <java> <kernel> <device kernel> <transfers>}: medians in nanoseconds; {@code java}
 *       is -1 when only the kernel was timed.</li>
 * </ul>
 */
public final class VerificationHarnessGenerator {

    public static final String PREFIX = "INSIGHT ";
    // Every finding starts with the prefix and the tag the harness is given, which the method under
    // test cannot know: a line it prints cannot pass for a finding
    private static final String TAG = "insightTag + \"";

    /** The nested class that holds the original method. */
    public static final String ORIGINAL_CLASS = "InsightOriginal";
    private static final String API = TornadoKernels.TORNADO_API;
    private static final String ARRAYS = API + "types.arrays.";
    private static final Set<String> SCALARS = Set.of("int", "long", "short", "byte", "char", "boolean", "float", "double");
    private static final Set<String> NATIVE_ARRAYS = Set.of("IntArray", "FloatArray", "DoubleArray", "LongArray",
            "ShortArray", "ByteArray", "CharArray");

    /**
     * A generated harness.
     *
     * @param source          the Java source
     * @param kernelFirstLine the first line of the kernel method in it, from 1
     * @param kernelLastLine  the last line of the kernel method
     */
    public record Source(@NotNull String source, int kernelFirstLine, int kernelLastLine) {
    }

    private enum Role { CONTEXT, INPUT, RESULT }

    private VerificationHarnessGenerator() {
    }

    /**
     * Generates the harness for {@code model}.
     *
     * @throws UnsupportedParameterTypeException if a parameter has a type the harness cannot fill
     *                                           or compare, or the kernel's parameters do not
     *                                           match the original's
     */
    public static @NotNull Source generate(@NotNull VerificationModel model) throws UnsupportedParameterTypeException {
        HarnessModel kernel = model.kernel();
        HarnessModel original = model.original();
        List<Role> roles = roles(model);
        String returnType = model.originalReturnType();
        if (!returnType.equals("void")) {
            checkSupported("the returned value", returnType);
        }

        List<String> userImports = new ArrayList<>(kernel.imports());
        userImports.addAll(original.imports());
        HarnessImports imports = new HarnessImports(userImports);
        String code = kernel.kernelSource() + String.join("\n", kernel.helperSources());
        if (Pattern.compile("(?<![\\w.])@Parallel\\b").matcher(code).find()) {
            imports.require(API + "annotations.Parallel");
        }
        if (Pattern.compile("(?<![\\w.])@Reduce\\b").matcher(code).find()) {
            imports.require(API + "annotations.Reduce");
        }

        StringBuilder body = new StringBuilder();
        body.append("public class ").append(kernel.className()).append(" {\n");
        for (Field field : kernel.fields()) {
            body.append(field(field, imports)).append(";\n");
        }
        int kernelOffset = body.length();
        body.append(kernel.kernelSource()).append("\n");
        int kernelEnd = body.length();
        for (String helper : kernel.helperSources()) {
            body.append(helper).append("\n");
        }
        body.append("\nstatic final class ").append(ORIGINAL_CLASS).append(" {\n");
        for (Field field : original.fields()) {
            body.append(field(field, imports)).append(";\n");
        }
        body.append(original.kernelSource()).append("\n");
        for (String helper : original.helperSources()) {
            body.append(helper).append("\n");
        }
        body.append("}\n");
        body.append(main(model, roles));
        body.append(HELPERS);
        body.append("}\n");

        String head = imports.source() + "\n";
        int firstLine = lines(head) + lines(body.substring(0, kernelOffset)) + 1;
        int lastLine = lines(head) + lines(body.substring(0, kernelEnd));
        return new Source(head + body, firstLine, lastLine);
    }

    private static int lines(String text) {
        return (int) text.chars().filter(c -> c == '\n').count();
    }

    private static String field(Field field, HarnessImports imports) {
        return HarnessGenerator.fieldDeclaration(new Field(field.modifiers(), imports.use(field.type()), field.name(),
                field.initializer()));
    }

    // What each kernel parameter is: a context, the original's parameter at the same position, or the result
    private static List<Role> roles(VerificationModel model) throws UnsupportedParameterTypeException {
        List<Role> roles = new ArrayList<>();
        int inputs = 0;
        for (Parameter parameter : model.kernelParameters()) {
            if (parameter.type().equals(TornadoKernels.KERNEL_CONTEXT) || parameter.type().equals(TornadoKernels.TILE_CONTEXT)) {
                roles.add(Role.CONTEXT);
            } else if (inputs < model.originalParameters().size()) {
                Parameter expected = model.originalParameters().get(inputs++);
                if (!expected.type().equals(parameter.type())) {
                    throw new UnsupportedParameterTypeException(parameter.name(), parameter.type());
                }
                checkSupported(parameter.name(), parameter.type());
                roles.add(Role.INPUT);
            } else if (!model.originalReturnType().equals("void") && !roles.contains(Role.RESULT)) {
                checkSupported(parameter.name(), parameter.type());
                if (SCALARS.contains(parameter.type())) {
                    throw new UnsupportedParameterTypeException(parameter.name(), parameter.type());
                }
                roles.add(Role.RESULT);
            } else {
                throw new UnsupportedParameterTypeException(parameter.name(), parameter.type());
            }
        }
        if (inputs < model.originalParameters().size()) {
            Parameter missing = model.originalParameters().get(inputs);
            throw new UnsupportedParameterTypeException(missing.name(), missing.type());
        }
        return roles;
    }

    /**
     * Why the harness cannot run a method with these parameters and this return type, as
     * {@link UnsupportedParameterTypeException}'s message, or {@code null} if it can fill them.
     * The kernel's own parameters are checked when the harness is generated.
     */
    public static @Nullable String unsupported(@NotNull List<VerificationModel.Parameter> parameters, @NotNull String returnType) {
        try {
            for (VerificationModel.Parameter parameter : parameters) {
                checkSupported(parameter.name(), parameter.type());
            }
            if (!returnType.equals("void")) {
                checkSupported("the returned value", returnType);
            }
            return null;
        } catch (UnsupportedParameterTypeException e) {
            return e.getMessage();
        }
    }

    private static void checkSupported(String name, String type) throws UnsupportedParameterTypeException {
        if (SCALARS.contains(type) || isPrimitiveArray(type) || isNativeArray(type)) {
            return;
        }
        throw new UnsupportedParameterTypeException(name, type);
    }

    private static boolean isPrimitiveArray(String type) {
        return type.endsWith("[]") && !type.endsWith("[][]") && SCALARS.contains(type.substring(0, type.length() - 2));
    }

    private static boolean isNativeArray(String type) {
        return type.startsWith(ARRAYS) && NATIVE_ARRAYS.contains(simpleName(type));
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    // Whether values of the type are compared exactly
    private static boolean exact(String type) {
        String lower = type.toLowerCase();
        return !lower.contains("float") && !lower.contains("double");
    }

    private static String main(VerificationModel model, List<Role> roles) {
        List<Parameter> parameters = model.kernelParameters();
        String returnType = model.originalReturnType();
        boolean returns = !returnType.equals("void");
        StringBuilder main = new StringBuilder();
        main.append("\nstatic String insightTag = \"").append(PREFIX).append("\";\n")
                .append("\npublic static void main(String[] insightArgs) {\n")
                .append("insightTag = \"").append(PREFIX).append("\" + insightArgs[4] + \" \";\n")
                .append("int insightLength = Integer.parseInt(insightArgs[0]);\n")
                .append("int insightWarmups = Integer.parseInt(insightArgs[1]);\n")
                .append("int insightRuns = Integer.parseInt(insightArgs[2]);\n")
                .append("boolean insightTimeJava = insightArgs[3].equals(\"java\");\n")
                // A timed run times the first data set only
                .append("for (int insightData = 1; insightData <= (insightRuns > 0 ? 1 : 2); insightData++) {\n")
                .append("System.out.println(").append(TAG).append("data \" + insightData);\n");
        for (int i = 0; i < parameters.size(); i++) {
            Parameter p = parameters.get(i);
            main.append(p.type()).append(' ').append(p.name()).append(" = ")
                    .append(roles.get(i) == Role.CONTEXT ? "new " + p.type() + "()" : zero(p.type())).append(";\n");
            if (roles.get(i) == Role.INPUT) {
                main.append(p.type()).append(" insightRef").append(i).append(" = ").append(zero(p.type())).append(";\n");
            }
        }
        if (returns) {
            main.append(returnType).append(" insightExpected = ").append(zero(returnType)).append(";\n");
        }
        main.append("Throwable insightFailure = null;\nboolean insightRan = false;\n")
                .append("for (int insightShape = 1; insightShape <= 3 && !insightRan; insightShape++) {\n")
                .append("int insightSide = insightSide(insightLength, insightShape);\n")
                .append("if (insightData == 2) {\ninsightSide |= 1;\n}\n")
                .append("int insightSize = insightPow(insightSide, insightShape);\n");
        for (int i = 0; i < parameters.size(); i++) {
            Parameter p = parameters.get(i);
            if (roles.get(i) == Role.INPUT) {
                main.append(fill(p.name(), p.type(), "insightSize", i + 1, true));
            } else if (roles.get(i) == Role.RESULT) {
                boolean array = returnType.endsWith("[]") || isNativeArray(returnType);
                main.append(fill(p.name(), p.type(), array ? "insightSize" : "1", 0, false));
            }
        }
        for (int i = 0; i < parameters.size(); i++) {
            if (roles.get(i) == Role.INPUT) {
                Parameter p = parameters.get(i);
                main.append("insightRef").append(i).append(" = ").append(copy(p.name(), p.type())).append(";\n");
            }
        }
        main.append("try {\n")
                .append(returns ? "insightExpected = " : "").append(originalCall(model, roles)).append(";\n")
                .append("insightRan = true;\n")
                .append("System.out.println(").append(TAG).append("shape \" + insightShape + \" \" + insightSide);\n")
                .append("} catch (Throwable insightE) {\ninsightFailure = insightE;\n}\n}\n")
                .append("if (!insightRan && insightData == 2) {\nSystem.out.println(").append(TAG)
                .append("data-skipped \" + insightOneLine(insightFailure));\ncontinue;\n}\n")
                .append("if (!insightRan) {\nSystem.out.println(").append(TAG)
                .append("original-failed \" + insightOneLine(insightFailure));\nreturn;\n}\n");

        List<String> transferred = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter p = parameters.get(i);
            arguments.add(p.name());
            if (roles.get(i) != Role.CONTEXT && !SCALARS.contains(p.type())) {
                transferred.add(p.name());
            }
        }
        String data = String.join(", ", transferred);
        main.append(API).append("TaskGraph insightGraph = new ").append(API).append("TaskGraph(\"insight\")\n")
                .append(".transferToDevice(").append(API).append("enums.DataTransferMode.EVERY_EXECUTION, ").append(data).append(")\n")
                .append(".task(\"kernel\", ").append(model.kernel().className()).append("::").append(model.kernel().kernelName())
                .append(arguments.isEmpty() ? "" : ", " + String.join(", ", arguments)).append(")\n")
                .append(".transferToHost(").append(API).append("enums.DataTransferMode.EVERY_EXECUTION, ").append(data).append(");\n");
        main.append(gridScheduler(model));

        main.append("try (").append(API).append("TornadoExecutionPlan insightPlan = new ").append(API)
                .append("TornadoExecutionPlan(insightGraph.snapshot())) {\n");
        if (!model.grid().isEmpty()) {
            main.append("insightPlan.withGridScheduler(insightScheduler);\n");
        }
        // With a bailout, TornadoVM runs the kernel in Java, and the profiler reports no time on the device
        main.append("insightPlan.withProfiler(").append(API).append("enums.ProfilerMode.SILENT);\n")
                .append("try {\n").append(API).append("TornadoExecutionResult insightChecked = insightPlan.execute();\n")
                .append("if (insightChecked.getProfilerResult().getDeviceKernelTime() <= 0) {\nSystem.out.println(").append(TAG)
                .append("not-on-device\");\nreturn;\n}\n")
                .append("} catch (Throwable insightE) {\nSystem.out.println(").append(TAG)
                .append("kernel-failed \" + insightOneLine(insightE));\nreturn;\n}\n")
                .append("boolean insightEqual = true;\n");
        int result = roles.indexOf(Role.RESULT);
        for (int i = 0; i < parameters.size(); i++) {
            Parameter p = parameters.get(i);
            if (roles.get(i) == Role.INPUT && !SCALARS.contains(p.type())) {
                main.append("insightEqual &= insightCompare(\"").append(p.name()).append("\", insightRef").append(i)
                        .append(", ").append(p.name()).append(", ").append(exact(p.type())).append(");\n");
            }
        }
        if (returns) {
            if (result < 0) {
                main.append("System.out.println(").append(TAG)
                        .append("mismatch the method returns a value, but the kernel has no parameter for the result\");\n")
                        .append("insightEqual = false;\n");
            } else {
                main.append("insightEqual &= insightCompare(\"the returned value (in ").append(parameters.get(result).name())
                        .append(")\", insightExpected, ").append(parameters.get(result).name()).append(", ")
                        .append(exact(returnType)).append(");\n");
            }
        }
        main.append("System.out.println(insightEqual ? ").append(TAG).append("equal\" : ").append(TAG).append("different\");\n");

        // Timing: the original on its copy of the data (unless the caller has its time already), the
        // kernel with transfers, as the user would run it; see insightWarmup and insightMore
        main.append("if (insightRuns > 0) {\n")
                .append("long insightJavaTime = -1;\n")
                .append("if (insightTimeJava) {\n")
                .append("long[] insightJava = new long[insightRuns];\nint insightTimed = 0;\nlong insightSpent = 0;\n")
                .append("for (int insightRun = 0; insightMore(insightTimed, insightRuns, insightSpent); insightRun++) {\n")
                .append("long insightStart = System.nanoTime();\n")
                .append("try {\n").append(originalCall(model, roles)).append(";\n} catch (Throwable insightE) {\n}\n")
                .append("long insightTime = System.nanoTime() - insightStart;\ninsightSpent += insightTime;\n")
                .append("if (!insightWarmup(insightRun, insightWarmups, insightTimed, insightTime)) {\ninsightJava[insightTimed++] = insightTime;\n}\n}\n")
                .append("insightJavaTime = insightMedian(java.util.Arrays.copyOf(insightJava, insightTimed));\n}\n")
                .append("System.out.println(").append(TAG).append("timing-kernel\");\n")
                .append("long[] insightTotal = new long[insightRuns];\nlong[] insightDevice = new long[insightRuns];\nlong[] insightCopies = new long[insightRuns];\n")
                .append("int insightTimed = 0;\nlong insightSpent = 0;\n")
                .append("for (int insightRun = 0; insightMore(insightTimed, insightRuns, insightSpent); insightRun++) {\n")
                .append("long insightStart = System.nanoTime();\n")
                .append(API).append("TornadoExecutionResult insightResult = insightPlan.execute();\n")
                .append("long insightTime = System.nanoTime() - insightStart;\ninsightSpent += insightTime;\n")
                .append("if (!insightWarmup(insightRun, insightWarmups, insightTimed, insightTime)) {\n")
                .append("insightTotal[insightTimed] = insightTime;\n")
                .append("insightDevice[insightTimed] = insightResult.getProfilerResult().getDeviceKernelTime();\n")
                .append("insightCopies[insightTimed] = insightResult.getProfilerResult().getDataTransfersTime();\n")
                .append("insightTimed++;\n")
                .append("}\n}\n")
                .append("System.out.println(").append(TAG).append("time \" + insightJavaTime")
                .append(" + \" \" + insightMedian(java.util.Arrays.copyOf(insightTotal, insightTimed))")
                .append(" + \" \" + insightMedian(java.util.Arrays.copyOf(insightDevice, insightTimed))")
                .append(" + \" \" + insightMedian(java.util.Arrays.copyOf(insightCopies, insightTimed)));\n")
                .append("}\n")
                .append("} catch (Throwable insightE) {\nSystem.out.println(").append(TAG)
                .append("kernel-failed \" + insightOneLine(insightE));\nreturn;\n}\n")
                .append("}\n}\n");
        return main.toString();
    }

    private static String originalCall(VerificationModel model, List<Role> roles) {
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            if (roles.get(i) == Role.INPUT) {
                Parameter p = model.kernelParameters().get(i);
                arguments.add(SCALARS.contains(p.type()) ? p.name() : "insightRef" + i);
            }
        }
        String target = model.originalStatic() ? ORIGINAL_CLASS : "new " + ORIGINAL_CLASS + "()";
        return target + "." + model.original().kernelName() + "(" + String.join(", ", arguments) + ")";
    }

    private static String gridScheduler(VerificationModel model) {
        List<String> grid = model.grid();
        if (grid.isEmpty()) {
            return "";
        }
        String workerGrid = API + "WorkerGrid" + grid.size() + "D";
        // With a work-group size, the grid is rounded up to a multiple of it
        List<String> sizes = new ArrayList<>();
        for (int i = 0; i < grid.size(); i++) {
            String size = "(int) (" + grid.get(i) + ")";
            sizes.add(i < model.localWork().size() ? "insightRoundUp(" + size + ", (int) (" + model.localWork().get(i) + "))" : size);
        }
        StringBuilder text = new StringBuilder();
        text.append(workerGrid).append(" insightWorkerGrid = new ").append(workerGrid).append("(")
                .append(String.join(", ", sizes)).append(");\n");
        if (!model.localWork().isEmpty()) {
            List<String> local = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                local.add(i < model.localWork().size() ? "(long) (" + model.localWork().get(i) + ")" : "1L");
            }
            text.append("insightWorkerGrid.setLocalWork(").append(String.join(", ", local)).append(");\n");
        }
        text.append(API).append("GridScheduler insightScheduler = new ").append(API)
                .append("GridScheduler(\"insight.kernel\", insightWorkerGrid);\n");
        return text.toString();
    }

    private static String zero(String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "int", "long", "short", "byte", "char", "float", "double" -> "(" + type + ") 0";
            default -> "null";
        };
    }

    // Assigns test data (or zeros) of length size to the variable; scalars get the side or a fixed value
    private static String fill(String name, String type, String size, int seed, boolean data) {
        if (SCALARS.contains(type)) {
            return name + " = " + scalar(type) + ";\n";
        }
        String element;
        String create;
        if (isPrimitiveArray(type)) {
            element = type.substring(0, type.length() - 2);
            create = "new " + element + "[" + size + "]";
        } else {
            element = switch (simpleName(type)) {
                case "IntArray" -> "int";
                case "FloatArray" -> "float";
                case "DoubleArray" -> "double";
                case "LongArray" -> "long";
                case "ShortArray" -> "short";
                case "ByteArray" -> "byte";
                default -> "char";
            };
            create = "new " + type + "(" + size + ")";
        }
        StringBuilder text = new StringBuilder(name + " = " + create + ";\n");
        String value = data ? value(element, seed) : zero(element);
        if (isPrimitiveArray(type)) {
            if (data) {
                text.append("for (int insightI = 0; insightI < ").append(size).append("; insightI++) {\n")
                        .append(name).append("[insightI] = ").append(value).append(";\n}\n");
            }
        } else {
            // Off-heap arrays start uninitialised
            text.append("for (int insightI = 0; insightI < ").append(size).append("; insightI++) {\n")
                    .append(name).append(".set(insightI, ").append(value).append(");\n}\n");
        }
        return text.toString();
    }

    private static String scalar(String type) {
        return switch (type) {
            case "int" -> "insightSide";
            case "long" -> "(long) insightSide";
            case "float" -> "1.5f";
            case "double" -> "1.5";
            case "boolean" -> "true";
            case "char" -> "'a'";
            default -> "(" + type + ") 3";
        };
    }

    // Values that differ from element to element, so that a wrong index shows (see insightValue)
    private static String value(String element, int seed) {
        String base = "insightValue(insightI, " + seed + ", insightData)";
        return switch (element) {
            case "float" -> "(float) " + base;
            case "double" -> base;
            case "boolean" -> "((int) (" + base + " * 101)) % 3 == 0";
            case "char" -> "(char) ('a' + ((int) (" + base + " * 101)) % 26)";
            default -> "(" + element + ") (" + base + " * 101)";
        };
    }

    private static String copy(String name, String type) {
        if (SCALARS.contains(type)) {
            return name;
        }
        return isPrimitiveArray(type) ? name + ".clone()" : type + ".fromArray(" + name + ".toHeapArray())";
    }

    private static final String HELPERS = """

            static int insightSide(int length, int shape) {
                return Math.max(2, (int) Math.round(Math.pow(length, 1.0 / shape)));
            }

            static int insightRoundUp(int size, int multiple) {
                return multiple <= 0 ? size : (size + multiple - 1) / multiple * multiple;
            }

            static int insightPow(int side, int shape) {
                int size = 1;
                for (int i = 0; i < shape; i++) {
                    size *= side;
                }
                return size;
            }

            // Values that differ from element to element: from 0.5 to 1.5 in data set 1, from -1 to 1
            // with every 7th a zero in data set 2
            static double insightValue(int i, int seed, int data) {
                long step = (i * 37L + seed * 11L) % 101;
                if (data == 1) {
                    return step / 101.0 + 0.5;
                }
                return i % 7 == 3 ? 0 : step / 50.5 - 1.0;
            }

            static double[] insightValues(Object value) {
                if (value == null) {
                    return new double[0];
                }
                if (value instanceof @ARRAYS@IntArray a) {
                    value = a.toHeapArray();
                } else if (value instanceof @ARRAYS@FloatArray a) {
                    value = a.toHeapArray();
                } else if (value instanceof @ARRAYS@DoubleArray a) {
                    value = a.toHeapArray();
                } else if (value instanceof @ARRAYS@LongArray a) {
                    value = a.toHeapArray();
                } else if (value instanceof @ARRAYS@ShortArray a) {
                    value = a.toHeapArray();
                } else if (value instanceof @ARRAYS@ByteArray a) {
                    value = a.toHeapArray();
                } else if (value instanceof @ARRAYS@CharArray a) {
                    value = a.toHeapArray();
                }
                if (value instanceof Number n) {
                    return new double[] {n.doubleValue()};
                }
                if (value instanceof Boolean b) {
                    return new double[] {b ? 1 : 0};
                }
                if (value instanceof Character c) {
                    return new double[] {c};
                }
                int length = java.lang.reflect.Array.getLength(value);
                double[] values = new double[length];
                for (int i = 0; i < length; i++) {
                    Object element = java.lang.reflect.Array.get(value, i);
                    values[i] = element instanceof Number n ? n.doubleValue()
                            : element instanceof Boolean b ? (b ? 1 : 0) : (Character) element;
                }
                return values;
            }

            static boolean insightCompare(String name, Object expected, Object actual, boolean exact) {
                double[] want = insightValues(expected);
                double[] got = insightValues(actual);
                if (got.length < want.length) {
                    System.out.println(insightTag + "mismatch " + name + ": " + want.length + " values expected, the kernel gives " + got.length);
                    return false;
                }
                int wrong = 0;
                int first = -1;
                for (int i = 0; i < want.length; i++) {
                    boolean same = exact || want[i] == got[i] ? want[i] == got[i]
                            : Double.isNaN(want[i]) ? Double.isNaN(got[i])
                            : Math.abs(want[i] - got[i]) <= 1e-3 * Math.max(1.0, Math.abs(want[i]));
                    if (!same) {
                        wrong++;
                        if (first < 0) {
                            first = i;
                        }
                    }
                }
                if (wrong > 0) {
                    System.out.println(insightTag + "mismatch " + name + ": " + wrong + " of " + want.length
                            + " values differ; at index " + first + " the method gives " + want[first]
                            + " and the kernel " + got[first]);
                }
                return wrong == 0;
            }

            // A run of over a second is warm enough: the JIT has compiled the method, and more runs
            // only make the user wait
            static boolean insightWarmup(int run, int warmups, int timed, long time) {
                return timed == 0 && run < warmups && time < 1_000_000_000L;
            }

            // At most the runs asked for, and once there are 3, no more than 10 seconds of them
            static boolean insightMore(int timed, int runs, long spent) {
                return timed < runs && (timed < 3 || spent < 10_000_000_000L);
            }

            static long insightMedian(long[] values) {
                long[] sorted = values.clone();
                java.util.Arrays.sort(sorted);
                return sorted[sorted.length / 2];
            }

            static String insightOneLine(Throwable failure) {
                StringBuilder text = new StringBuilder();
                for (Throwable t = failure; t != null && text.length() < 1500; t = t.getCause() == t ? null : t.getCause()) {
                    if (!text.isEmpty()) {
                        text.append(" | caused by ");
                    }
                    text.append(t.getClass().getName()).append(": ").append(t.getMessage());
                }
                String line = text.toString().replaceAll("\\\\s+", " ");
                return line.length() > 1500 ? line.substring(0, 1500) + "..." : line;
            }
            """.replace("@ARRAYS@", ARRAYS);
}
