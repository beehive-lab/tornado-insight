package uk.ac.manchester.beehive.tornado.plugins.listener;

import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import uk.ac.manchester.beehive.tornado.plugins.message.TornadoTaskRefreshListener;
import org.jetbrains.annotations.NotNull;

public class EditorSwitch implements FileEditorManagerListener {

    //When editor selection changed, refresh the window tool
    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        //ProblemMethods.getInstance().clear();
        Project project = event.getManager().getProject();
        if (ToolWindowManager.getInstance(project).getToolWindow("TornadoVM") == null ||
            event.getNewFile() == null) return;


        TornadoTaskRefreshListener tornadoTaskRefreshListener =
                project.getMessageBus().syncPublisher(TornadoTaskRefreshListener.REFRESH_TOPIC);
        tornadoTaskRefreshListener.refresh(project,event.getNewFile());
    }

}
