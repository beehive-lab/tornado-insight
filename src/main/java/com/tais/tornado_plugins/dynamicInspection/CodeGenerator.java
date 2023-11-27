package com.tais.tornado_plugins.dynamicInspection;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.tais.tornado_plugins.util.MessageUtils;
import com.tais.tornado_plugins.util.MethodUtil;
import org.apache.commons.lang.RandomStringUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

public class CodeGenerator {

    public static void fileCreationHandler(Project project, ArrayList<PsiMethod> methods, String importCodeBlock) throws IOException {
        HashMap<String, PsiMethod> methodFile = new HashMap<>();
        File dir = FileUtilRt.createTempDirectory("files", null);
        for (PsiMethod method : methods) {
            String fileName = method.getName() + RandomStringUtils.randomAlphanumeric(5);
            File file = creatFile(project, method, importCodeBlock, fileName, dir);
            methodFile.put(file.getAbsolutePath(), method);
        }
        ExecutionEngine executionEngine = new ExecutionEngine(project, dir.getAbsolutePath(), methodFile);
        executionEngine.run();
    }

    private static File creatFile(Project project, PsiMethod method, String importCodeBlock, String filename, File dir) {
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
                """;
        StringBuilder methodWithParameters = new StringBuilder();
        String methodWithClass = filename + "::" + method.getName();
        String variableInit = VariableInit.variableInitHelper(method);

        for (PsiParameter p : method.getParameterList().getParameters()) {
            methodWithParameters.append(", ").append(p.getName());
        }
        String mainCode = "public static void main(String[] args) {\n" +
                "\n" +
                variableInit +
                "        TaskGraph taskGraph = new TaskGraph(\"s0\") \n" +
                "                .task(\"t0\", " + methodWithClass + methodWithParameters + "); \n" +
                "        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();\n" +
                "        TornadoExecutionPlan executor = new TornadoExecutionPlan(immutableTaskGraph);\n" +
                "        executor.withWarmUp();\n" +
                "    }";
        MessageUtils.getInstance(project).showInfoMsg("Info",variableInit);
        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(javaFile))) {
            System.out.println(javaFile.getPath());
            bufferedWriter.write(importCode + importCodeBlock);
            bufferedWriter.write("\n");
            bufferedWriter.write("public class " + javaFile.getName().replace(".java", "") + "{");
            bufferedWriter.write(MethodUtil.makePublicStatic(method));
            bufferedWriter.write(mainCode);
            bufferedWriter.write("}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return javaFile;
    }
}
