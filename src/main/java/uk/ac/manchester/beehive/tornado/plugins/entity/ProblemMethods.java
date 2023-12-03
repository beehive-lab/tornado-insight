package uk.ac.manchester.beehive.tornado.plugins.entity;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.message.TornadoTaskRefreshListener;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Singleton class responsible for maintaining a collection of problematic methods
 * that may not be compatible with TornadoVM processing.
 * It provides mechanisms to add, retrieve and clear these problematic methods.
 * Additionally, it triggers the appropriate listeners to update other components
 * of the system upon changes in the set of problematic methods.
 */
public class ProblemMethods {

    // Thread-safe set of methods to avoid concurrent modification issues.
    private final CopyOnWriteArraySet<String> methodSet = new CopyOnWriteArraySet<>();

    // Singleton instance of ProblemMethods.
    private static final ProblemMethods instance = new ProblemMethods();

    // Private constructor to ensure only one instance of the class can be created.
    private ProblemMethods() {
    }

    /**
     * Provides access to the singleton instance of ProblemMethods.
     *
     * @return the single instance of ProblemMethods.
     */
    public static ProblemMethods getInstance() {
        return instance;
    }

    /**
     * Adds a problematic method to the set and triggers a refresh if the method
     * is newly added.
     *
     * @param method The PSI representation of the problematic method.
     */
    public void addMethod(Project project, PsiFile file, PsiMethod method) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (methodSet.add(method.getText()) && virtualFile != null) {
            project.getMessageBus().
                    syncPublisher(TornadoTaskRefreshListener.REFRESH_TOPIC).refresh(project, virtualFile);
        }
    }

    /**
     * Provides access to the set of problematic methods.
     *
     * @return a set containing the textual representation of problematic methods.
     */
    public CopyOnWriteArraySet<String> getMethodSet() {
        return methodSet;
    }

    /**
     * Clears all the problematic methods from the set.
     */
    public void clear() {
        methodSet.clear();
    }
}
