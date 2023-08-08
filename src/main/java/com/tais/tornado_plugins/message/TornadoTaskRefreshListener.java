package com.tais.tornado_plugins.message;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.util.messages.Topic;

import java.util.List;

public interface TornadoTaskRefreshListener {
    Topic<TornadoTaskRefreshListener> TOPIC =
            Topic.create("Tornado task update", TornadoTaskRefreshListener.class);

    void refresh();
}
