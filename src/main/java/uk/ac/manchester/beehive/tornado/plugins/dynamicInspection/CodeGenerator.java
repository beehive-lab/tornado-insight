/*
 * Copyright (c) 2023, 2025, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;

public class CodeGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom random = new SecureRandom();

    private static String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    public static void fileCreationHandler(Project project, List<String> data) throws IOException {
        HashMap<String, PsiMethod> methodFile = new HashMap<>();
        ArrayList<PsiMethod> methods = TornadoTWTask.getMethods(data);
        ArrayList<PsiMethod> others = TornadoTWTask.getCalledMethods(methods);
        Map<String, Object> fields = TornadoTWTask.getFields();
        String importCodeBlock = TornadoTWTask.getImportCodeBlock();
        boolean saveFileEnabled = TornadoSettingState.getInstance().saveFileEnabled;

        PsiFile psiFile = TornadoTWTask.getPsiFile();
        List<TornadoTWTask.TaskGraphTransfer> transfers = TornadoTWTask.extractTaskGraphTransfers(psiFile);

        File dir = FileUtilRt.createTempDirectory("files", null);
        for (PsiMethod method : methods) {
            String fileName = method.getName() + randomAlphanumeric(5);
            File file = createFile(method, others, fields, importCodeBlock, transfers, fileName, dir);
            if (saveFileEnabled) {
                saveFileToDisk(file, TornadoSettingState.getInstance().debugFileSaveLocation);
            }
            methodFile.put(file.getAbsolutePath(), method);
        }
        ExecutionEngine executionEngine = new ExecutionEngine(project, dir.getAbsolutePath(), methodFile);
        executionEngine.run();
    }

    private static File createFile(PsiMethod method, ArrayList<PsiMethod> others, Map<String, Object> fields, String importCodeBlock, List<TornadoTWTask.TaskGraphTransfer> transfers, String filename,
            File dir) {
        File javaFile;
        try {
            javaFile = FileUtilRt.createTempFile(dir, filename, ".java", true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String importCode = """
                import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
                import uk.ac.manchester.tornado.api.TaskGraph;
                import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.annotations.Reduce;
                import uk.ac.manchester.tornado.api.enums.DataTransferMode;
                import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
                import uk.ac.manchester.tornado.api.types.HalfFloat;
                """;

        String methodWithClass = filename + "::" + method.getName();

        // The class may declare several TaskGraphs that reference this kernel by
        // name; prefer the one whose .task(...) argument count matches the
        // kernel's parameter count so we don't pick a stale/unrelated TaskGraph.
        int kernelParamCount = method.getParameterList().getParametersCount();
        Optional<String> maybeOriginalTaskGraph = TornadoTWTask.extractOriginalTaskGraphDeclaration(TornadoTWTask.getPsiFile(), method.getName(), methodWithClass, kernelParamCount);
        Optional<List<TornadoTWTask.TaskParametersInfo>> taskParametersInfos = TornadoTWTask.extractTasksParameters(TornadoTWTask.getPsiFile(), method.getName(), kernelParamCount);
        String taskParameters = getTaskParameters(method, taskParametersInfos, fields);
        String mainCode = getTaskGraphCode(method, maybeOriginalTaskGraph, taskParameters, methodWithClass);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(javaFile))) {
            writer.write(importCode + importCodeBlock);
            writer.write("\n");
            writer.write("public class " + javaFile.getName().replace(".java", "") + " {\n");

            for (Map.Entry<String, Object> field : fields.entrySet()) {
                writer.write(field.getKey());
                if (field.getValue() != null) {
                    writer.write(" = " + field.getValue());
                }
                writer.write(";\n");
            }

            writer.write(method.getText());
            for (PsiMethod other : others) {
                writer.write(other.getText());
                writer.write("\n");
            }

            writer.write(mainCode);
            writer.write("}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return javaFile;
    }

    private static @NotNull String getTaskParameters(PsiMethod method,
                                                     Optional<List<TornadoTWTask.TaskParametersInfo>> taskParametersInfos,
                                                     Map<String, Object> fields) {
        Set<String> localMemoryParams = LocalMemoryParameterAnalyzer.findLocalMemoryParameters(method);
        List<TornadoTWTask.TaskParametersInfo> params = taskParametersInfos.orElse(Collections.emptyList());

        if (params.isEmpty()) {
            // Fallback: infer from the method signature when TaskGraphs are not declared
            Map<String, String> intOverrides = collectIntParamOverrides(
                    methodIntParameterNames(method), fields);
            return VariableInit.variableInitHelper(method, localMemoryParams, intOverrides);
        }

        // The arguments passed to .task("t0", Class::kernel, arg0, arg1, ...)
        // map positionally onto the kernel's parameters. TornadoVM requires the
        // argument type to match the kernel parameter type exactly (these are
        // concrete types with no subtyping), so when the counts line up we
        // allocate each harness variable with the KERNEL parameter's type
        // rather than the type resolved from the user's source variable. The
        // latter can be a mismatch (e.g. an IntArray variable passed to a
        // LongArray/DoubleArray kernel parameter), which would generate code
        // that does not compile against the strongly-typed TaskGraph.task(...)
        // overloads. We keep the user's argument NAMES so the reused TaskGraph
        // (which references them) still resolves.
        PsiParameter[] kernelParams = method.getParameterList().getParameters();
        boolean alignWithKernel = params.size() == kernelParams.length;

        ArrayList<String> names = new ArrayList<>(params.size());
        ArrayList<String> types = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            TornadoTWTask.TaskParametersInfo v = params.get(i);
            names.add(v.getName());
            String kernelType = alignWithKernel && kernelParams[i].getTypeElement() != null
                    ? kernelParams[i].getTypeElement().getText()
                    : null;
            types.add(kernelType != null ? kernelType : v.getType());
        }
        Map<String, String> intOverrides = collectIntParamOverrides(intParamNames(names, types), fields);
        return VariableInit.variableInitHelper(names, types, localMemoryParams, intOverrides);
    }

    private static List<String> methodIntParameterNames(PsiMethod method) {
        List<String> intNames = new ArrayList<>();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if ("int".equals(p.getTypeElement().getText())) {
                intNames.add(p.getName());
            }
        }
        return intNames;
    }

    private static List<String> intParamNames(List<String> names, List<String> types) {
        List<String> intNames = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            if ("int".equals(types.get(i))) {
                intNames.add(names.get(i));
            }
        }
        return intNames;
    }

    /**
     * For each int parameter of the kernel, look in the user's class fields
     * for an existing int field whose name matches the parameter name
     * (case-insensitive) - e.g. parameter 'size' picks up a user-defined
     * 'SIZE' or 'Size' constant. Returned map values are the field names to
     * reference; unmatched parameters are omitted so VariableInit falls back
     * to the configured parameterSize literal.
     */
    private static Map<String, String> collectIntParamOverrides(List<String> intParamNames,
                                                                Map<String, Object> fields) {
        if (intParamNames.isEmpty() || fields.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> overrides = new HashMap<>();
        for (String paramName : intParamNames) {
            String matched = findMatchingIntField(fields, paramName);
            if (matched != null) {
                overrides.put(paramName, matched);
            }
        }
        return overrides;
    }

    private static String findMatchingIntField(Map<String, Object> fields, String paramName) {
        String target = paramName.toLowerCase();
        for (String key : fields.keySet()) {
            // Field keys are formatted "<modifiers> <type> <name>" by
            // TornadoTWTask.getFields(); split on whitespace and inspect the
            // last two tokens.
            String[] parts = key.trim().split("\\s+");
            if (parts.length < 2) continue;
            String fieldName = parts[parts.length - 1];
            String fieldType = parts[parts.length - 2];
            if (!"int".equals(fieldType)) continue;
            if (fieldName.toLowerCase().equals(target)) {
                return fieldName;
            }
        }
        return null;
    }

    private static @NotNull String getTaskGraphCode(PsiMethod method, Optional<String> maybeOriginalTaskGraph, String variableInit, String methodWithClass) {
        boolean isChainComplete = maybeOriginalTaskGraph.isPresent() && maybeOriginalTaskGraph.get().contains(".task(");
        String mainCode;

        if (isChainComplete) {
            // Extract the TaskGraph variable name from the declaration
            String taskGraphVarName = maybeOriginalTaskGraph
                    .flatMap(TornadoTWTask::extractTaskGraphVariableName)
                    .orElse("taskGraph");

            mainCode = """
                    \n\tpublic static void main(String[] args) throws TornadoExecutionPlanException {
                    %s
                    %s
                    ImmutableTaskGraph immutableTaskGraph = %s.snapshot();
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {
                        executionPlan.withPreCompilation();
                    }
                    }
                    """.formatted(variableInit, maybeOriginalTaskGraph.get(), taskGraphVarName);
        } else {
            // Fallback: Dynamically build TaskGraph from method parameters
            StringBuilder taskParameters = new StringBuilder();
            StringBuilder taskGraphParameters = new StringBuilder();

            for (PsiParameter p : method.getParameterList().getParameters()) {
                taskParameters.append(", ").append(p.getName());
                if (isParameterBoxedType(p)) {
                    taskGraphParameters.append(", ").append(p.getName());
                }
            }
            mainCode = "\n\tpublic static void main(String[] args) throws TornadoExecutionPlanException {\n" + //
                    "\n" + //
                    variableInit + //
                    "TaskGraph taskGraph = new TaskGraph(\"insightTaskGraphName\") \n" + //
                    ".transferToDevice(DataTransferMode.EVERY_EXECUTION" + taskGraphParameters + ")\n" + //
                    ".task(\"insightTaskName\", " + methodWithClass + taskParameters + ") \n" + //
                    ".transferToHost(DataTransferMode.EVERY_EXECUTION" + taskGraphParameters + ");\n" + //
                    "ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();\n" + //
                    "try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {\n" + //
                    "executionPlan.withPreCompilation();\n" + //
                    "        }\n" + //
                    "    }"; //
        }
        return mainCode;
    }

    private static boolean isParameterBoxedType(PsiParameter p) {
        return switch (p.getTypeElement().getText()) {
            case "int", "float", "double", "long", "boolean" -> false;
            default -> true;
        };
    }

    private static void saveFileToDisk(File sourceFile, String targetDir) {
        File target = new File(targetDir);
        File targetFile = new File(target, sourceFile.getName());
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(targetFile))) {
            bufferedWriter.write(new String(Files.readAllBytes(sourceFile.toPath())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
