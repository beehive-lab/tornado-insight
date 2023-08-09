package com.tais.tornado_plugins.message;

import com.intellij.util.messages.Topic;

public interface TornadoTaskRefreshListener {
    Topic<TornadoTaskRefreshListener> TOPIC =
            Topic.create("Tornado task update", TornadoTaskRefreshListener.class);

    void refresh();
}
