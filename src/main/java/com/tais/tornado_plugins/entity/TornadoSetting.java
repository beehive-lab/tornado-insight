package com.tais.tornado_plugins.entity;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the settings associated with the TornadoVM plugin, allowing
 * for persistent storage and retrieval of configuration details.
 * The settings are persisted across IDE sessions and stored in 'tornado.xml'.
 */
@State(name = "tornadovm", storages = {@Storage(value = "tornado.xml")})
public class TornadoSetting implements PersistentStateComponent<TornadoSetting> {

    // File path for the TornadoVM environment variable file.
    public String setVarFile;

    /**
     * Retrieves the singleton instance of the TornadoSetting.
     * This ensures only one instance of settings is used across the application.
     *
     * @return The singleton instance of TornadoSetting.
     */
    public static TornadoSetting getInstance() {
        return ApplicationManager.getApplication().getService(TornadoSetting.class);
    }

    /**
     * Retrieves the current state of the TornadoSetting. This is used
     * by the IDE to determine any changes and save them if necessary.
     *
     * @return The current state of the TornadoSetting.
     */
    @Override
    public @Nullable TornadoSetting getState() {
        return this;
    }

    /**
     * Loads the settings state from the provided state object.
     * This is triggered when the IDE is started, or when the plugin is loaded.
     *
     * @param state The TornadoSetting object that represents the saved state.
     */
    @Override
    public void loadState(@NotNull TornadoSetting state) {
        this.setVarFile = state.setVarFile;
    }
}
