package com.tais.tornado_plugins.service;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.tais.tornado_plugins.entity.Method;
import com.tais.tornado_plugins.entity.MethodsCollection;
import com.tais.tornado_plugins.mockExecution.ExecutionEngine;
import com.tais.tornado_plugins.mockExecution.VariableInit;
import com.tais.tornado_plugins.ui.TaskParametersDialogWrapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class TWTasksButtonEvent {
    public void pressButton(){
        List selectedValuesList = TornadoToolsWindow.getToolsWindow().getTasksList().getSelectedValuesList();
        if (selectedValuesList.isEmpty()) System.out.println("None Selected");
        ArrayList<PsiMethod> methodList = TornadoTWTask.getMethods(selectedValuesList);
        new TaskParametersDialogWrapper(methodList).showAndGet();
    }

    public void fileCreationHandler(MethodsCollection methodsCollection) throws IOException {
        HashMap<String, Method> methodFile = new HashMap<>();
        File dir = FileUtilRt.createTempDirectory("files",null);
        for (Method method:methodsCollection.getMethodArrayList()) {
            System.out.println(method.getParameterValues());
            System.out.println("To device: " + method.getToDeviceParameters());
            String fileName = method.getMethod().getName() + method.getMethod().hashCode();
            File file = creatFile(method, fileName,dir);
            methodFile.put(file.getAbsolutePath(), method);
        }
        ExecutionEngine executionEngine = new ExecutionEngine(dir.getAbsolutePath(), methodFile);
        executionEngine.run();
        dir.delete();
    }

    private File creatFile(Method method, String filename, File dir){
        File javaFile;
        try {
            javaFile = FileUtilRt.createTempFile(dir, filename, ".java", true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //TODO:May need more import code
        String importCode = "import uk.ac.manchester.tornado.api.ImmutableTaskGraph;\n" +
                "import uk.ac.manchester.tornado.api.TaskGraph;\n" +
                "import uk.ac.manchester.tornado.api.TornadoExecutionPlan;\n" +
                "import uk.ac.manchester.tornado.api.annotations.Parallel;\n" +
                "import uk.ac.manchester.tornado.api.annotations.Reduce;\n" +
                "import uk.ac.manchester.tornado.api.enums.DataTransferMode;";
        StringBuilder parametersIn = new StringBuilder();
        StringBuilder methodWithParameters = new StringBuilder();
        StringBuilder parameterOut = new StringBuilder();
        String methodWithClass = filename +"::"+ method.getMethod().getName();
        String variableInit = VariableInit.variableInitHelper(method);

        for (PsiParameter p: method.getToDeviceParameters()) {
            if (parametersIn.isEmpty()){
                parametersIn.append(p.getName());
            }else {
                parametersIn.append(", ").append(p.getName());
            }
        }

        for (PsiParameter p: method.getMethod().getParameterList().getParameters()) {
            methodWithParameters.append(", ").append(p.getName());
        }

        for (PsiParameter p: method.getToHostParameters()) {
            if (parameterOut.isEmpty()){
                parameterOut.append(p.getName());
            }else {
                parameterOut.append(", ").append(p.getName());
            }
        }

        String mainCode = "public static void main(String[] args) {\n" +
                "\n" +
                variableInit +
                "        TaskGraph taskGraph = new TaskGraph(\"s0\") \n" +
                "                .transferToDevice(DataTransferMode.FIRST_EXECUTION, "+ parametersIn +") \n" +
                "                .task(\"t0\", "+methodWithClass +methodWithParameters + ") \n" +
                "                .transferToHost(DataTransferMode.EVERY_EXECUTION, "+ parameterOut +");\n" +
                "\n" +
                "        ImmutableTaskGraph immutableTaskGraph = taskGraph.snapshot();\n" +
                "        TornadoExecutionPlan executor = new TornadoExecutionPlan(immutableTaskGraph);\n" +
                "        executor.execute();\n" +
                "    }";

        try (BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(javaFile))) {
            System.out.println(javaFile.getPath());
            bufferedWriter.write(importCode);
            bufferedWriter.write("\n");
            bufferedWriter.write("public class "+javaFile.getName().replace(".java","")+"{");
            bufferedWriter.write(method.getMethod().getText());
            bufferedWriter.write(mainCode);
            bufferedWriter.write("}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return javaFile;
    }

}
