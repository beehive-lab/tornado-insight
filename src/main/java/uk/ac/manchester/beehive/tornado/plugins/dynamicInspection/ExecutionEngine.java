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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.entity.EnvironmentVariable;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageUtils;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ExecutionEngine {

    private final String tempFolderPath;

    private final HashMap<String, PsiMethod> fileMethodMap;

    private final Project project;

    private boolean success;

    public ExecutionEngine(Project project, String tempFolderPath, HashMap<String, PsiMethod> fileMethodMap) {
        this.project = project;
        this.tempFolderPath = tempFolderPath;
        this.fileMethodMap = fileMethodMap;
        this.success = false;
    }

    public void run(){
        // Performing UI related operations on a non-EDT is not allowed.
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.start"));
        long startTime = System.currentTimeMillis();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ArrayList<String> files = new ArrayList<>(fileMethodMap.keySet());
            try {
                compile(tempFolderPath, files);
                packFolder(tempFolderPath, tempFolderPath);
                executeJars(tempFolderPath);
            }catch (UnsupportedOperationException ignore){}
            catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                long runningTime = System.currentTimeMillis() - startTime;
                showStatDialog(runningTime);
                cleanUp();
            }
        });
    }

    private void compile(String outputDir, ArrayList<String> javaFiles) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.compile"));
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath(TornadoSettingState.getInstance().getJavaHome() + "/bin/javac");
        commandLine.addParameter("--release");
        commandLine.addParameter("21");
        commandLine.addParameter("--enable-preview");
        commandLine.addParameter("-g");
        commandLine.addParameter("-classpath");
        commandLine.addParameter(TornadoSettingState.getInstance().getApiPath()+
                File.pathSeparator + TornadoSettingState.getInstance().getMatricesPath()+
                File.pathSeparator + TornadoSettingState.getInstance().getUnitTestPath());
        commandLine.addParameter("-d");
        commandLine.addParameter(outputDir);
        commandLine.addParameters(javaFiles);  // Adds each Java file to the command line

        // Execute the command
        try {
            ProcessOutput output = ExecUtil.execAndGetOutput(commandLine);
            int exitCode = output.getExitCode();
            String stderr = output.getStderr();
            if (exitCode == 1) {
                MessageUtils.getInstance(project).showErrorMsg("Internal error when running generated code", "Exit code: " + exitCode + "\n" + stderr);
                throw new UnsupportedOperationException("Compilation failed with exit code " + exitCode);
            }
        } catch (ExecutionException e) {
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    MessageBundle.message("dynamic.error.compile"));
        }
    }

    private void packFolder(String classFolderPath, String outputFolderPath) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.packing"));
        File classFolder = new File(classFolderPath);
        File[] classFiles = classFolder.listFiles((dir, name) -> name.endsWith(".class"));
        if (classFiles == null) {
            return;
        }

        File outputFolder = new File(outputFolderPath);
        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
        }

        for (File classFile : classFiles) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, classFile.getName().replace(".class", ""));

            File outputJar = new File(outputFolder, classFile.getName().replace(".class", ".jar"));

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar), manifest)) {
                Path classPath = classFile.toPath();
                jos.putNextEntry(new JarEntry(classPath.getFileName().toString()));
                Files.copy(classPath, jos);
                jos.closeEntry();
            } catch (IOException e) {
                MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                        MessageBundle.message("dynamic.error.packing"));
            }
        }
    }

    private void executeJars(String jarFolderPath) {
        MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                MessageBundle.message("dynamic.info.execution"));
        GeneralCommandLine commandLine = new GeneralCommandLine();
        //Detecting if the user has correctly installed TornadoVM
        String sourceFile = TornadoSettingState.getInstance().setVarsPath();
        commandLine.setExePath("/bin/sh");
        commandLine.addParameter("-c");

        StringBuilder command = new StringBuilder();
        retrieveEnvironmentVariablesCommand(command);
        command.append("tornado --device");

        commandLine.addParameter(command.toString());
        try {
            CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
            ProcessOutput output = handler.runProcess();
            if (output.getExitCode() != 0) {
                // TornadoVM is not properly installed on the user's machine
                Notification notification = new Notification("Print", "TornadoVM not detected",
                        "TornadoVM is not properly installed or configured", NotificationType.ERROR);
                notification.addAction(new NotificationAction("How to install and configure TornadoVM") {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                        BrowserUtil.browse("https://tornadovm.readthedocs.io/en/latest/installation.html#");
                    }
                });
                Notifications.Bus.notify(notification);
                return;
            }
        } catch (ExecutionException ignored) {
            MessageUtils.getInstance(project).showErrorMsg(MessageBundle.message("dynamic.info.title"),
                    "TornadoVM environment variable file is not set correctly.");

            return;
        }

        File folder = new File(jarFolderPath);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null) {
            System.out.println("No files found in the specified directory.");
            return;
        }

        for (File file : listOfFiles) {
            if (file.isFile() && file.getName().endsWith(".jar")) {
                runTornadoOnJar(file.getAbsolutePath());
            }
        }
    }

    private void runTornadoOnJar(String jarPath) {
        GeneralCommandLine commandLine = getGeneralCommandLine(jarPath);
        //commandLine.addParameter("source " + sourceFile + ";tornado --debug --printKernel -jar " + jarPath);
        try {
            CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
            ProcessOutput output = handler.runProcess();
            //Cannot use the exit code to determine if TornadoVM is running with an error or not.
            // Under normal circumstances Tornado output will also have error output For example:
            // " WARNING: Using incubator modules: jdk.incubator.foreign, jdk.incubator.vector "
            printResults(jarPath, output.toString().contains("Exception"), output);
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    @NotNull
    private GeneralCommandLine getGeneralCommandLine(String jarPath) {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath("/bin/sh");
        commandLine.addParameter("-c");

        StringBuilder command = new StringBuilder();
        retrieveEnvironmentVariablesCommand(command);
        command.append("tornado --printKernel").append(emitPrintBytecode()).append("-jar ").append(jarPath);

        commandLine.addParameter(command.toString());
        return commandLine;
    }

    private String emitPrintBytecode() {
        boolean bytecodeVisualizerEnabled = TornadoSettingState.getInstance().bytecodeVisualizerEnabled;
        return (bytecodeVisualizerEnabled) ? (" --jvm=\"-Dtornado.dump.bytecodes.dir=" + TornadoSettingState.getInstance().bytecodesFileSaveLocation + "\" ") : (" ");
    }

    private void retrieveEnvironmentVariablesCommand(StringBuilder command) {
        if (EnvironmentVariable.getJavaHome() != null) {
            command.append("export JAVA_HOME=").append(EnvironmentVariable.getJavaHome()).append(";");
        }
        if (EnvironmentVariable.getTornadoSdk() != null) {
            command.append("export TORNADO_SDK=").append(EnvironmentVariable.getTornadoSdk()).append(";");
        }
        if (EnvironmentVariable.getTornadoSdk() != null) {
            command.append("export PATH=").append(EnvironmentVariable.getTornadoSdk()).append("/bin:$PATH;");
        } else if (EnvironmentVariable.getPath() != null) {
            command.append("export PATH=").append(EnvironmentVariable.getPath()).append(";");
        }
        if (EnvironmentVariable.getCmakeRoot() != null) {
            command.append("export CMAKE_ROOT=").append(EnvironmentVariable.getCmakeRoot()).append(";");
        }
    }

    //Test results for each method
    private void printResults(String jarPath, boolean hasException, ProcessOutput output) {
        String javaPath = jarPath.substring(0, jarPath.lastIndexOf(".jar")) + ".java";
        ApplicationManager.getApplication().runReadAction(() -> {
            String methodName = TornadoTWTask.psiMethodFormat(fileMethodMap.get(javaPath));
            if (hasException) {
                MessageUtils consoleInstance = MessageUtils.getInstance(project);
                consoleInstance.showErrorMsg(MessageBundle.message("dynamic.info.title"),methodName + ": " + output.getStderr());
                consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.title"),MessageBundle.message("dynamic.info.documentation"));
                consoleInstance.showInfoMsg(MessageBundle.message("dynamic.info.title"),MessageBundle.message("dynamic.info.bug"));
            } else {
                MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.title"),
                        methodName + ": " + MessageBundle.message("dynamic.info.noException") );
                MessageUtils.getInstance(project).showInfoMsg(MessageBundle.message("dynamic.info.opencl"), output.getStdout());
            }
            success = true;
        });
    }

    private void showStatDialog(long runningTime){
        if (success){
            Notification notification = new Notification("Print", MessageBundle.message("dynamic.info.statistics.title"),
                    MessageBundle.message("dynamic.info.statistics.body") + " " + runningTime + "ms", NotificationType.INFORMATION);
            notification.addAction(new ChangeParameterSize());
            ApplicationManager.getApplication().invokeLater(() -> Notifications.Bus.notify(notification, project));
        }
    }

    private void cleanUp(){
        File file = new File(tempFolderPath);
        try {
            FileUtils.deleteDirectory(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static class ChangeParameterSize extends NotificationAction {
        public ChangeParameterSize() {
            super(MessageBundle.message("dynamic.parameterSize.button"));
        }
        @Override
        public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), "TornadoVM");
            notification.expire();
        }
    }
}
