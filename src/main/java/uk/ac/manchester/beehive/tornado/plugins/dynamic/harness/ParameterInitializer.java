/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.Parameter;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the declaration, with test data, of one harness variable.
 * <p>
 * The type of a {@link Parameter} is its canonical text: fully qualified when it
 * resolves (e.g. {@code uk.ac.manchester.tornado.api.types.arrays.IntArray}), as
 * written otherwise. Values are chosen by the simple name; the declaration and the
 * constructor call use the type as given, so they compile without extra imports.
 * A type without a known initialisation is rejected with
 * {@link UnsupportedParameterTypeException} rather than left uninitialised.
 * </p>
 */
final class ParameterInitializer {

    private static final int LOCAL_MEMORY_PARAM_MAX = 16;

    // TornadoVM vector types: element type and width, e.g. Int16, Half4
    private static final Pattern TUPLE = Pattern.compile("^(Int|Byte|Short|Float|Double|Half)(\\d+)$");

    // The vector types the TornadoVM API declares
    private static final Map<String, Set<Integer>> TUPLE_WIDTHS = Map.of(
            "Int", Set.of(2, 3, 4, 8, 16),
            "Float", Set.of(2, 3, 4, 8, 16),
            "Double", Set.of(2, 3, 4, 8, 16),
            "Half", Set.of(2, 3, 4, 8, 16),
            "Byte", Set.of(3, 4),
            "Short", Set.of(2, 3));

    private final int parameterSize;
    private final Random r;

    ParameterInitializer(int parameterSize, @NotNull Random random) {
        this.parameterSize = parameterSize;
        this.r = random;
    }

    /**
     * Returns the statements that declare and initialise {@code parameter}.
     *
     * @throws UnsupportedParameterTypeException if no test data can be generated for its type
     */
    @NotNull String declaration(@NotNull Parameter parameter) throws UnsupportedParameterTypeException {
        String name = parameter.name();
        String type = parameter.type();
        return "\t\t" + type + " " + name + initializer(type, name, parameter);
    }

    static @NotNull String simpleName(@NotNull String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    private String initializer(String type, String name, Parameter parameter) throws UnsupportedParameterTypeException {
        int size = parameterSize;
        String simple = simpleName(type);
        switch (simple) {
            // Scalar int parameters either reuse a user-defined constant /
            // field that matches the parameter name (resolved by the model
            // builder, e.g. parameter 'size' picking up an existing 'SIZE'
            // field), or fall back to the configured parameterSize so
            // kernel loop bounds stay in sync with the array lengths the
            // harness just allocated. Local-memory parameters keep their
            // constrained-random initialisation.
            case "int":
                return "=" + (parameter.localMemorySize()
                        ? generateConstrainedInt()
                        : parameter.intOverride() != null ? parameter.intOverride() : String.valueOf(size)) + ";";
            case "long":
            case "short":
            case "byte":
            case "char":
            case "boolean":
            case "float":
            case "double":
                return "=" + value(simple) + ";";
            case "HalfFloat":
                return "= new " + type + "(" + value("float") + ");";
            case "int[]":
            case "long[]":
            case "short[]":
            case "byte[]":
            case "char[]":
            case "boolean[]":
            case "float[]":
            case "double[]":
                return arrayInit(simple.substring(0, simple.length() - 2));
            case "IntArray":
                return arrayTypeInit(type, name, value("int"));
            case "Int8Array":
            case "ByteArray":
                return arrayTypeInit(type, name, value("byte"));
            case "ShortArray":
                return arrayTypeInit(type, name, value("short"));
            case "LongArray":
                return arrayTypeInit(type, name, value("long"));
            case "CharArray":
                return arrayTypeInit(type, name, value("char"));
            case "FloatArray":
                return arrayTypeInit(type, name, value("float"));
            case "DoubleArray":
                return arrayTypeInit(type, name, value("double"));
            case "HalfFloatArray":
                return arrayTypeInit(type, name, value("HalfFloat"));
            case "Matrix2DInt":
                return matrix2DInit(type, name, "int");
            case "Matrix2DFloat":
            case "Matrix2DFloat4":
                return matrix2DInit(type, name, "float");
            case "Matrix2DDouble":
                return matrix2DInit(type, name, "double");
            case "Matrix3DInt":
                return matrix3DInit(type, name, "int");
            case "Matrix3DShort":
                return matrix3DInit(type, name, "short");
            case "Matrix3DLong":
                return matrix3DInit(type, name, "long");
            case "Matrix3DFloat":
            case "Matrix3DFloat4":
                return matrix3DInit(type, name, "float");
            case "Matrix3DDouble":
                return matrix3DInit(type, name, "double");
            case "Matrix4x4Float":
                return " = new " + type + "();\n" + name + ".fill(" + value("float") + ");";
            case "ImageByte3":
            case "ImageByte4":
                return imageInit(type, name, "byte");
            case "ImageFloat":
            case "ImageFloat3":
            case "ImageFloat4":
            case "ImageFloat8":
                return imageInit(type, name, "float");
            case "VectorInt":
            case "VectorInt2":
            case "VectorInt3":
            case "VectorInt4":
            case "VectorInt8":
            case "VectorInt16":
                return vectorInit(type, name, "int");
            case "VectorFloat":
            case "VectorFloat2":
            case "VectorFloat3":
            case "VectorFloat4":
            case "VectorFloat8":
            case "VectorFloat16":
                return vectorInit(type, name, "float");
            case "VectorDouble":
            case "VectorDouble2":
            case "VectorDouble3":
            case "VectorDouble4":
            case "VectorDouble8":
            case "VectorDouble16":
                // a float literal: VectorDouble4.fill takes a float, the others a double
                return vectorInit(type, name, "float");
            case "VectorHalf":
            case "VectorHalf2":
            case "VectorHalf3":
            case "VectorHalf4":
            case "VectorHalf8":
            case "VectorHalf16":
                return vectorInit(type, name, "HalfFloat");
            case "KernelContext":
            case "TileContext":
                return " = new " + type + "();";
            case "AtomicInteger":
                return "= new " + type + "(" + value("int") + ");";
            default:
                Matcher tuple = TUPLE.matcher(simple);
                if (tuple.matches() && TUPLE_WIDTHS.get(tuple.group(1)).contains(Integer.parseInt(tuple.group(2)))) {
                    return tupleInit(type, tuple.group(1), Integer.parseInt(tuple.group(2)));
                }
                throw new UnsupportedParameterTypeException(name, type);
        }
    }

    private String arrayInit(String primitive) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("={");
        stringBuilder.append(value(primitive));
        for (int i = 0; i < parameterSize - 1; i++) {
            stringBuilder.append(",").append(value(primitive));
        }
        stringBuilder.append("};");
        return stringBuilder.toString();
    }

    private String arrayTypeInit(String type, String name, String value) {
        return "= new " + type + "(" + parameterSize + ");" + name + ".init(" + value + ");";
    }

    private String tupleInit(String type, String element, int width) {
        String primitive = element.equals("Half") ? "HalfFloat" : element.toLowerCase();
        StringBuilder builder = new StringBuilder();
        builder.append(" = new ").append(type).append("(");
        for (int i = 0; i < width; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append(value(primitive));
        }
        builder.append(");");
        return builder.toString();
    }

    private String matrix2DInit(String type, String name, String primitive) {
        return " = new " + type + "(" + parameterSize + "," + parameterSize + ");" + "\n" +
                name + ".fill(" + value(primitive) + ");";
    }

    private String matrix3DInit(String type, String name, String primitive) {
        return " = new " + type + "(" + parameterSize + "," + parameterSize + "," + parameterSize + ");" + "\n" +
                name + ".fill(" + value(primitive) + ");";
    }

    private String imageInit(String type, String name, String primitive) {
        return " = new " + type + "(" + parameterSize + "," + parameterSize + ");" + "\n" +
                name + ".fill(" + value(primitive) + ");";
    }

    private String vectorInit(String type, String name, String primitive) {
        return " = new " + type + "(" + parameterSize + ");" + "\n" +
                name + ".fill(" + value(primitive) + ");";
    }

    /**
     * Returns a random literal of a primitive type, or a {@code HalfFloat} construction.
     */
    String value(String primitive) {
        return switch (primitive) {
            case "int" -> "" + r.nextInt(50);
            case "long" -> r.nextInt(50) + "L";
            case "short" -> "(short)" + r.nextInt(50);
            case "byte" -> "(byte)" + r.nextInt(127);
            case "char" -> "'" + (char) (r.nextInt(26) + 'a') + "'";
            case "boolean" -> String.valueOf(r.nextBoolean());
            case "float" -> r.nextFloat(50) + "f";
            case "double" -> "" + r.nextDouble(50);
            case "HalfFloat" -> "new HalfFloat(" + r.nextFloat(50) + "f)";
            default -> throw new IllegalArgumentException(primitive);
        };
    }

    private String generateConstrainedInt() {
        return "" + (r.nextInt(LOCAL_MEMORY_PARAM_MAX) + 1);
    }
}
