package uk.ac.manchester.beehive.tornado.plugins.service;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import uk.ac.manchester.beehive.tornado.plugins.message.TornadoTaskRefreshListener;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class RefreshAction extends AnAction{

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Objects.requireNonNull(e.getProject()).getMessageBus().
                syncPublisher(TornadoTaskRefreshListener.REFRESH_TOPIC).refresh();
    }
}
