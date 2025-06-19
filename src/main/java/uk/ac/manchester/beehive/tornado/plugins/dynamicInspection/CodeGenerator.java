/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageUtils;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;

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
        File dir = FileUtilRt.createTempDirectory("files", null);
        for (PsiMethod method : methods) {
            String fileName = method.getName() + randomAlphanumeric(5);
            File file = createFile(project, method, others, fields, importCodeBlock, fileName, dir);
            if (saveFileEnabled) {
                saveFileToDisk(file, TornadoSettingState.getInstance().fileSaveLocation);
            }
            methodFile.put(file.getAbsolutePath(), method);
        }
        ExecutionEngine executionEngine = new ExecutionEngine(project, dir.getAbsolutePath(), methodFile);
        executionEngine.run();
    }

    private static File createFile(Project project, PsiMethod method, ArrayList<PsiMethod> others, Map<String, Object> fields, String importCodeBlock, String filename, File dir) {
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
                import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
                import uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException;
                """;
        StringBuilder taskParameters = new StringBuilder();
        StringBuilder taskGraphParameters = new StringBuilder();
        String methodWithClass = filename + "::" + method.getName();
        String variableInit = VariableInit.variableInitHelper(method);

        for (PsiParameter p : method.getParameterList().getParameters()) {
            taskParameters.append(", ").append(p.getName());
            if (isParameterBoxedType(p)) {
                taskGraphParameters.append(", ").append(p.getName());
            }
        }
        String mainCode = "\n\tpublic static void main(String[] args) throws TornadoExecutionPlanException {\n" + //
                "\n" + //
                variableInit + //
                "TaskGraph taskGraph = new TaskGraph(\"s0\") \n" + //
                ".transferToDevice(DataTransferMode.EVERY_EXECUTION" + taskGraphParameters + ")\n" + //
                ".task(\"t0\", " + methodWithClass + taskParameters + ") \n" + //
                ".transferToHost(DataTransferMode.EVERY_EXECUTION" + taskGraphParameters + ");\n" + //
                "ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();\n" + //
                "try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(immutableTaskGraph)) {\n" + //
                "executionPlan.execute();\n" + //
                "        }\n" + //
                "    }"; //
        MessageUtils.getInstance(project).showInfoMsg("Info", variableInit);
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(javaFile))) {
            System.out.println(javaFile.getPath());
            bufferedWriter.write(importCode + importCodeBlock);
            bufferedWriter.write("\n");
            bufferedWriter.write("public class " + javaFile.getName().replace(".java", "") + "{");
            bufferedWriter.write("\n");
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                bufferedWriter.write(field.getKey());
                if (field.getValue() != null) {
                    bufferedWriter.write(" = " + field.getValue());
                }
                bufferedWriter.write("; \n");
            }
            bufferedWriter.write(method.getText());
            for (PsiMethod other : others) {
                String methodText = other.getText();
                bufferedWriter.write(methodText);
                bufferedWriter.write("\n");
            }

            bufferedWriter.write(mainCode);
            bufferedWriter.write("}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return javaFile;
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
