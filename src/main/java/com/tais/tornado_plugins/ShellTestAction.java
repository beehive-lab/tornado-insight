package com.tais.tornado_plugins;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;

import java.io.File;
import java.io.InputStreamReader;
import java.io.BufferedReader;

import com.intellij.openapi.util.io.StreamUtil;
import com.tais.tornado_plugins.util.StreamGobbler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ShellTestAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        assert project != null;;
        System.out.println(project.getBasePath());
        //runProcessBuilder(project.getBasePath());
        executeShellCommand(project.getBasePath());
    }

    public void executeShellCommand(String path) {
//        String command = "ls"; // 要执行的 Shell 命令
//
//        GeneralCommandLine commandLine = new GeneralCommandLine();
//        commandLine.setExePath("/bin/bash"); // 指定 Shell 解释器路径
//        ParametersList parametersList = commandLine.getParametersList();
//        parametersList.add(command);
//
//        try {
//            ProcessOutput result1 = ExecUtil.execAndGetOutput(commandLine);
//            System.out.println(result1.getStdout());
//
//        } catch (ExecutionException e) {
//            e.printStackTrace();
//        }
        Application application = ApplicationManager.getApplication();
        application.executeOnPooledThread(() -> {
            try {
                String[] cmd = {
                        "/bin/sh",
                        "-c",
                        "javac -d bin src/*.java",
                        "java -cp bin Main"
                };

                Process process = Runtime.getRuntime().exec(cmd,null,new File(path));
                cmd = new String[]{
                        "/bin/sh",
                        "-c",
                        "java -cp bin Main"
                };
                Process process1 = Runtime.getRuntime().exec(cmd,null,new File(path));
                String output = StreamUtil.readText(process1.getInputStream(), StandardCharsets.UTF_8);
                System.out.println(output);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void runProcessBuilder(String path){
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        ProcessBuilder builder = new ProcessBuilder();
        builder.command("javac "+ path + "/src/Main.java", "java","-cp",path+"src/","Main");
        builder.directory(new File(System.getProperty("user.home")));
        try {
            Process process = builder.start();
            StreamGobbler streamGobbler = new StreamGobbler(process.getInputStream(), System.out::println);
            executorService.submit(streamGobbler);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
