package com.tais.tornado_plugins.service;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.tais.tornado_plugins.message.TornadoTaskRefreshListener;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class RefreshAction extends AnAction{

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Objects.requireNonNull(e.getProject()).getMessageBus().
                syncPublisher(TornadoTaskRefreshListener.REFRESH_TOPIC).refresh();
    }
}
