package uk.ac.manchester.beehive.tornado.plugins.listener;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ex.ToolWindowManagerListener;
import uk.ac.manchester.beehive.tornado.plugins.message.TornadoTaskRefreshListener;
import org.jetbrains.annotations.NotNull;

public class ToolWindowOpen implements ToolWindowManagerListener {

    @Override
    public void toolWindowShown(@NotNull ToolWindow toolWindow) {
        ToolWindowManagerListener.super.toolWindowShown(toolWindow);
        if (toolWindow.getId().equals("TornadoVM")) {
            toolWindow.getProject().getMessageBus().syncPublisher(TornadoTaskRefreshListener.REFRESH_TOPIC).refresh();
        }
    }
}
