package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;

import java.io.IOException;
import java.util.ArrayList;

public class DynamicInspection {
    public static void process(Project project, ArrayList<PsiMethod> methodArrayList){
        try {
            CodeGenerator.fileCreationHandler(project,methodArrayList, TornadoTWTask.getImportCodeBlock());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
