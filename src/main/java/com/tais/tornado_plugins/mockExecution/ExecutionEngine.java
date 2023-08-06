package com.tais.tornado_plugins.mockExecution;

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
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.tais.tornado_plugins.entity.Method;
import org.jetbrains.annotations.NotNull;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ResourceBundle;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ExecutionEngine {
    private static final String jars = "/Users/tais/Coding/Tornado_Plugins/Tornado_Plugins/src/main/resources/tornado-api-0.15.2.jar:" +
            "/Users/tais/Coding/Tornado_Plugins/Tornado_Plugins/src/main/resources/tornado-matrices-0.15.2.jar";
    public static void run(HashMap<String, Method> fileMethodHashMap){
        Application application = ApplicationManager.getApplication();
        application.executeOnPooledThread(() ->{
            ArrayList<String> files = new ArrayList<>(fileMethodHashMap.keySet());
            try {
                compile(jars,"/Users/tais/Downloads/source",files);
                packFolder("/Users/tais/Downloads/source","/Users/tais/Downloads/source");
                executeJars("/Users/tais/Downloads/source");
            } catch (ExecutionException | IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static String getJavacPath(Project project) {
        Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
        if (sdk != null && sdk.getSdkType() instanceof JavaSdkType) {
            return ((JavaSdkType) sdk.getSdkType()).getBinPath(sdk) + (System.getProperty("os.name").startsWith("Win") ? "\\javac.exe" : "/javac");
        }
        return null;  // Consider throwing an exception if no appropriate JDK is found.
    }

    private static void compile(String classpath, String outputDir, ArrayList<String> javaFiles) throws ExecutionException {
            //String javacPath = getJavacPath(ProjectManager.getInstance().getDefaultProject());
//            if (javacPath == null) {
//                throw new IllegalStateException("Javac path not found!");
//            }

            GeneralCommandLine commandLine = new GeneralCommandLine();
            commandLine.setExePath("javac");
            commandLine.addParameter("-classpath");
            commandLine.addParameter(classpath);
            commandLine.addParameter("-d");
            commandLine.addParameter(outputDir);
            commandLine.addParameters(javaFiles);  // Adds each Java file to the command line

            // Execute the command
            try {
                System.out.println(ExecUtil.execAndGetOutput(commandLine));
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
    }

    private static void packFolder(String classFolderPath, String outputFolderPath) throws IOException {
        File classFolder = new File(classFolderPath);
        File[] classFiles = classFolder.listFiles((dir, name) -> name.endsWith(".class"));

        if (classFiles == null) {
            System.out.println("No .class files found in the specified input folder.");
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
            }
        }
    }

    private static void executeJars(String jarFolderPath){
        GeneralCommandLine commandLine = new GeneralCommandLine();
        //Detecting if the user has correctly installed TornadoVM
        commandLine.setExePath("tornado");
        commandLine.addParameter("--device");
        try {
            CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
            ProcessOutput output = handler.runProcess();
            if (output.getExitCode() != 0) {
                // TornadoVM is not properly installed on the user's machine
                Notification notification = new Notification("Print", "TornadoVM not detected",
                        "TornadoVM is not properly installed or configured", NotificationType.ERROR);
                notification.addAction(new NotificationAction("Get information on how to install and configure TornadoVM") {
                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                        BrowserUtil.browse("https://tornadovm.readthedocs.io/en/latest/installation.html#");
                    }
                });
                Notifications.Bus.notify(notification);
                return;
            }
        } catch (ExecutionException ignored) {}

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

    private static void runTornadoOnJar(String jarPath){
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.setExePath("tornado");
        commandLine.addParameter("--fullDebug");
        commandLine.addParameter("-jar");
        commandLine.addParameter(jarPath);
        try {
            CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
            ProcessOutput output = handler.runProcess();

            if (output.getExitCode() != 0) {
                // Print error output if the command failed
                System.err.println(output.getStderr());
            } else {
                System.out.println(output.getStdout());
            }
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }
}
