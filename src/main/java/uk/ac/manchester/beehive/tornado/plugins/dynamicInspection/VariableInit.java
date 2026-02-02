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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.util.Set;

public class VariableInit {

    private static final int LOCAL_MEMORY_PARAM_MAX = 16;

    private static int parameterSize;
    private static String tensorShapeDimension;
    private static int[] tensorShapeDimensions;

    public static String variableInitHelper(@NotNull PsiMethod method) {
        return variableInitHelper(method, Collections.emptySet());
    }

    public static String variableInitHelper(@NotNull PsiMethod method, @NotNull Set<String> localMemoryParams) {
        initializeSizes();
        ArrayList<String> parametersName = new ArrayList<>();
        ArrayList<String> parametersType = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parametersType.add(parameter.getTypeElement().getText());
            parametersName.add(parameter.getName());
        }
        return variableInit(parametersName, parametersType, localMemoryParams);
    }

    public static String variableInitHelper(ArrayList<String> fieldNames, ArrayList<String> fieldTypes) {
        return variableInitHelper(fieldNames, fieldTypes, Collections.emptySet());
    }

    public static String variableInitHelper(ArrayList<String> fieldNames, ArrayList<String> fieldTypes,
                                            @NotNull Set<String> localMemoryParams) {
        initializeSizes();
        return variableInit(fieldNames, fieldTypes, localMemoryParams);
    }

    private static void initializeSizes() {
        parameterSize = TornadoSettingState.getInstance().parameterSize;
        tensorShapeDimension = TornadoSettingState.getInstance().tensorShapeDimensions;
        tensorShapeDimensions = convertShapeStringToIntArray(tensorShapeDimension);
    }

    private static String variableInit(@NotNull ArrayList<String> parametersName, ArrayList<String> parametersType){
        return variableInit(parametersName, parametersType, Collections.emptySet());
    }

    private static String variableInit(@NotNull ArrayList<String> parametersName, ArrayList<String> parametersType,
                                       @NotNull Set<String> localMemoryParams){
        StringBuilder returnString = new StringBuilder();
        int size = parametersName.size();
        for (int i = 0; i < size; i++) {
            returnString.append("\t\t");
            String name = parametersName.get(i);
            String type = parametersType.get(i);
            returnString.append(type).append(" ").append(name);
            boolean constrain = localMemoryParams.contains(name);
            String value = lookupBoxedTypes(type, name, parameterSize, constrain);
            returnString.append(value);
            returnString.append("\n");
        }
        return returnString.toString();
    }

    private static String lookupBoxedTypes(String type, String name, int size){
        return lookupBoxedTypes(type, name, size, false);
    }

    private static String lookupBoxedTypes(String type, String name, int size, boolean constrainForLocalMemory){
        return switch (type) {
            case "int" -> "=" + (constrainForLocalMemory ? generateConstrainedInt() : generateValueByType("Int")) + ";";
            case "float" -> "=" + generateValueByType("Float") + ";";
            case "double" -> "=" + generateValueByType("Double") + ";";
            case "HalfFloat" -> "= new HalfFloat(" + generateValueByType("HalfFloat") + ");";
            case "int[]", "float[]", "double[]", "byte[]" -> arrayInit(type);
            case "Int2", "Int3", "Int4", "Int8", "Int16",
                    "Byte2", "Byte3", "Byte4", "Byte8",
                    "Double2", "Double3", "Double4", "Double8", "Double16",
                    "Float2", "Float3", "Float4", "Float8", "Float16",
                    "Half2", "Half3", "Half4", "Half8", "Half16",
                    "Short2", "Short3" -> tupleInit(type);
            case "IntArray" -> "= new IntArray(" + size + ");" + name + ".init(" + generateValueByType("Int") + ");";
            case "ShortArray" -> "= new ShortArray(" + size + ");" + name + ".init((short)" + generateValueByType("Short") + ");";
            case "DoubleArray" ->
                    "= new DoubleArray(" + size + ");" + name + ".init(" + generateValueByType("Double") + ");";
            case "FloatArray" ->
                    "= new FloatArray(" + size + ");" + name + ".init(" + generateValueByType("Float") + ");";
            case "HalfFloatArray" ->
                    "= new HalfFloatArray(" + size + ");" + name + ".init(new HalfFloat(" + generateValueByType("HalfFloat") + "));";
            case "CharArray" ->
                    "= new CharArray(" + size + ");" + name + ".init(" + generateValueByType("Char") + ");";
            case "ByteArray" ->
                    "= new ByteArray(" + size + ");" + name + ".init(" + generateValueByType("Byte") + ");";
            case "LongArray" ->
                    "= new LongArray(" + size + ");" + name + ".init(" + generateValueByType("Long") + ");";
            case "Matrix2DFloat", "Matrix2DFloat4", "Matrix2DDouble", "Matrix2DInt" -> matrix2DInit(type, name, "Int");
            case "Matrix3DInt" -> matrix3DInit(type, name, "Int");
            case "Matrix3DShort" -> matrix3DInit(type, name, "Short");
            case "Matrix3DFloat", "Matrix3DFloat4", "Matrix" -> matrix3DInit(type, name, "Float");
            case "Matrix3DLong" -> matrix3DInit(type, name, "Long");
            case "Matrix3DDouble" -> matrix3DInit(type, name, "Double");
            case "Matrix4x4Float" -> matrix1DInit(type, name, "Float");
            case "ImageByte3", "ImageByte4" -> imageInit(type,name, "Byte");
            case "ImageFloat", "ImageFloat3", "ImageFloat4", "ImageFloat8" -> imageInit(type, name, "Float");
            case "VectorInt", "VectorInt2", "VectorInt3", "VectorInt4", "VectorInt8", "VectorInt16" -> vectorInit(name, type, "Int");
            case "VectorFloat", "VectorFloat2", "VectorFloat3", "VectorFloat4", "VectorFloat8", "VectorFloat16" -> vectorInit(name, type, "Float");
            case "VectorDouble", "VectorDouble2", "VectorDouble3", "VectorDouble4", "VectorDouble8", "VectorDouble16" -> vectorInit(name, type, "Double");
            case "VectorHalf", "VectorHalf2", "VectorHalf3", "VectorHalf4", "VectorHalf8", "VectorHalf16" -> vectorHalfInit(name, type);
            case "KernelContext" -> " = new KernelContext();";
            case "TensorByte", "TensorFP16", "TensorFP32", "TensorFP64", "TensorInt16", "TensorInt32", "TensorInt64" -> tensorInit(type);
            default -> "";
        };
    }

    private static String arrayInit(String type){
        String primitive = type.split("\\[]")[0];
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("={");
        stringBuilder.append(generateValueByType(primitive));
        for (int i = 0; i < parameterSize - 1; i++){
            stringBuilder.append(",").append(generateValueByType(primitive));
        }
        stringBuilder.append("};");
        return stringBuilder.toString();
    }
    private static String tupleInit(String type){
        String primitiveType = type.substring(0, type.length()-1);
        int size = type.charAt(type.length()-1) - '0';
        System.out.println(primitiveType);
        StringBuilder builder = new StringBuilder();
        builder.append(" = new ").append(type).append("(");
        builder.append(generateValueByType(primitiveType));
        for (int i = 0; i < size - 1; i++){
            builder.append(",");
            builder.append(generateValueByType(primitiveType));
        }
        builder.append(");");

        return builder.toString();
    }

    private static String matrix1DInit(String type, String name, String primitive){
        return " = new " + type + "();" +
                "for (int i = 0; i <" + name + ".size()" + "; i++) { " +
                name + ".set(i, " + generateValueByType(primitive) + ");" +
                "}";
    }

    private static String matrix2DInit(String type, String name, String primitive){
        return " = new " + type + "(" + parameterSize + "," + parameterSize + ");" +
                "for (int i = 0; i <" + parameterSize + "; i++) { " +
                "for (int j = 0; j < " + parameterSize + "; j++) {" + "\n" +
                name + ".set(i, j, " + generateValueByType(primitive) + ");" +
                "}" +
                "}";
    }

    private static String matrix3DInit(String type, String name, String primitive){
        if (primitive.equals("Short")){
            String castedType = primitive.toLowerCase();
            String emittedCastedOperation = ".fill((" + castedType + ")";
            return " = new " + type + "(" + parameterSize + "," + parameterSize + "," + parameterSize + ");" + "\n" +
                    name + emittedCastedOperation  + generateValueByType(primitive) + ");";
        }
        return " = new " + type + "(" + parameterSize + "," + parameterSize + "," + parameterSize + ");" + "\n" +
                name  + ".fill(" + generateValueByType(primitive) + ");";
    }

    private static String imageInit(String type, String name, String primitive){
        return " = new " + type + "(" + parameterSize + "," + parameterSize + ");" + "\n" +
                name + ".fill(" + generateValueByType(primitive) + ");";
    }

    private static String vectorInit(String name, String type, String innerType){
        return " = new " + type + "(" + parameterSize + ");" + "\n" +
                name + ".fill(" + generateValueByType(innerType) + ");";
    }

    private static String vectorHalfInit(String name, String type){
        return " = new " + type + "(" + parameterSize + ");" + "\n" +
                name + ".fill(new HalfFloat(" + generateValueByType("HalfFloat") + "));";
    }

    private static String tensorInit(String type){
        StringBuilder builder = new StringBuilder();
        builder.append(" = new ").append(type).append("(").append("new Shape(");

        for (int i = 0; i < tensorShapeDimensions.length; i++){
            builder.append(tensorShapeDimensions[i]);
            if (i < tensorShapeDimensions.length - 1){
                builder.append(", ");
            }
        }
        builder.append("));").append("\n");
        return builder.toString();
    }

    private static int[] convertShapeStringToIntArray(String shapeString){
        String[] stringArray = shapeString.split(",");
        int[] numbers = new int[stringArray.length];

        for (int i = 0; i < stringArray.length; i++) {
            if (!stringArray[i].trim().isEmpty()) {
                numbers[i] = Integer.parseInt(stringArray[i].trim());
            }
        }
        return numbers;
    }

    private static String generateValueByType(String type){
        Random r = new Random();
        return switch (type) {
            case "Int", "int", "Short", "short" -> "" + r.nextInt(1000);
            case "Long", "long" -> "" + r.nextLong(1000);
            case "Float", "float", "HalfFloat" -> r.nextFloat(1000) + "f";
            case "Double","double" -> "" + r.nextDouble(1000);
            case "Byte","byte" -> "(byte)" + r.nextInt(127);
            case "Char", "char" -> "'" + (char)(r.nextInt(26) + 'a') + "'";
            default -> "";
        };
    }

    private static String generateConstrainedInt() {
        Random r = new Random();
        return "" + (r.nextInt(LOCAL_MEMORY_PARAM_MAX) + 1);
    }

}
