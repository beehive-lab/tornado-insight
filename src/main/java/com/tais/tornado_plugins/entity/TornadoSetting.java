package com.tais.tornado_plugins.entity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "tornadovm", storages = {@Storage(value = "tornado.xml")})
public class TornadoSetting implements PersistentStateComponent<TornadoSetting> {
    public String setVarFile;

    public static TornadoSetting getInstance() {
        return ApplicationManager.getApplication().getService(TornadoSetting.class);
    }

    @Override
    public @Nullable TornadoSetting getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull TornadoSetting state) {
        this.setVarFile = state.setVarFile;
    }
}
