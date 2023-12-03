package uk.ac.manchester.beehive.tornado.plugins.message;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.Topic;

public interface TornadoTaskRefreshListener {
    @Topic.ProjectLevel
    Topic<TornadoTaskRefreshListener> REFRESH_TOPIC =
            Topic.create("Tornado task update", TornadoTaskRefreshListener.class);

    void refresh();

    void refresh(Project project, VirtualFile newFile);
}
