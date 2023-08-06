package com.tais.tornado_plugins.mockExecution;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.util.ExecUtil;
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
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ResourceBundle;

public class ExecutionEngine {
    private static final String jars = "/Users/tais/Coding/Tornado_Plugins/Tornado_Plugins/src/main/resources/tornado-api-0.15.2.jar:" +
            "/Users/tais/Coding/Tornado_Plugins/Tornado_Plugins/src/main/resources/tornado-matrices-0.15.2.jar";
    public static void run(HashMap<String, Method> fileMethodHashMap){
        Application application = ApplicationManager.getApplication();
        application.executeOnPooledThread(() ->{
            ArrayList<String> files = new ArrayList<>(fileMethodHashMap.keySet());
            try {
                compile(jars,"/Users/tais/Downloads/source",files);
            } catch (ExecutionException e) {
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

    public static void compile(String classpath, String outputDir, ArrayList<String> javaFiles) throws ExecutionException {
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
}
