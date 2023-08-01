package com.tais.tornado_plugins.service;

import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;
import com.tais.tornado_plugins.ui.TornadoVM;
import com.tais.tornado_plugins.util.TornadoTWTask;
import org.apache.tools.ant.taskdefs.Java;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class TWTasksButtonEvent {
    public void pressButton(){
        List selectedValuesList = TornadoToolsWindow.getToolsWindow().getTasksList().getSelectedValuesList();
        if (selectedValuesList.isEmpty()) System.out.println("None Selected");
        ArrayList<PsiMethod> methodList = TornadoTWTask.getMethods(selectedValuesList);
        creatFile(methodList);
    }

    private void creatFile(List<PsiMethod> methodList){
        File javaFile;
        try {
             javaFile = FileUtilRt.createTempFile("testCode", ".java", true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String importCode = "import uk.ac.manchester.tornado.api.ImmutableTaskGraph;\n" +
                "import uk.ac.manchester.tornado.api.TaskGraph;\n" +
                "import uk.ac.manchester.tornado.api.TornadoExecutionPlan;\n" +
                "import uk.ac.manchester.tornado.api.annotations.Parallel;\n" +
                "import uk.ac.manchester.tornado.api.enums.DataTransferMode;";

        String parametersIn = "";
        String methodWithParameters = "";
        String parameterOut = "";

        String mainCode = "public static void main(String[] args) {\n" +
                "\n" +
                "        TaskGraph taskGraph = new TaskGraph(\"s0\") //\n" +
                "                .transferToDevice(DataTransferMode.FIRST_EXECUTION, "+ parametersIn +") //\n" +
                "                .task(\"t0\", "+ methodWithParameters + ") //\n" +
                "                .transferToHost(DataTransferMode.EVERY_EXECUTION, "+ parameterOut +");\n" +
                "\n" +
                "        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();\n" +
                "        TornadoExecutionPlan executor = new TornadoExecutionPlan(immutableTaskGraph);\n" +
                "        executor.execute();\n" +
                "    }";

        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(javaFile))) {
            System.out.println(javaFile.getPath());
            bufferedWriter.write(importCode);
            bufferedWriter.write("public class "+javaFile.getName().replace(".java","")+"{");
            for (PsiMethod method:methodList) {
                bufferedWriter.write(method.getText());
            }
            bufferedWriter.write(mainCode);
            bufferedWriter.write("}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
