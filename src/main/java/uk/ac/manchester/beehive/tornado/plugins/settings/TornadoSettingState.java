/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
 *  The University of Manchester.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the settings associated with the TornadoVM plugin, allowing
 * for persistent storage and retrieval of configuration details.
 * The settings are persisted across IDE sessions and stored in 'tornado.xml'.
 */
@State(name = "TornadoVMSettingsState", storages = {@Storage(value = "TornadoSettings.xml")})
public class TornadoSettingState implements PersistentStateComponent<TornadoSettingState> {

    // Exclusive upper bound for parameterSize, shared by the Settings-dialog
    // validator, the runtime check in DynamicInspectionPipeline and the user-facing
    // messages, so valid sizes are 1..MAX_PARAMETER_SIZE - 1.
    public static final int MAX_PARAMETER_SIZE = 65536;

    // Default test-data size used by the harness generator for synthetic
    // inputs for dynamic inspection. Picked to be large enough that kernels
    // actually exercise real data, while staying within the Settings-dialog
    // validator (>0 and <MAX_PARAMETER_SIZE). Overridden when the user changes
    // the value in Settings.
    public static final int DEFAULT_PARAMETER_SIZE = 128;
    public int parameterSize = DEFAULT_PARAMETER_SIZE;
    public boolean saveFileEnabled;
    public String debugFileSaveLocation;

    /**
     * Retrieves the singleton instance of the TornadoSetting.
     * This ensures only one instance of settings is used across the application.
     *
     * @return The singleton instance of TornadoSetting.
     */
    public static TornadoSettingState getInstance() {
        return ApplicationManager.getApplication().getService(TornadoSettingState.class);
    }

    /**
     * Retrieves the current state of the TornadoSetting. This is used
     * by the IDE to determine any changes and save them if necessary.
     *
     * @return The current state of the TornadoSetting.
     */
    @Override
    public @Nullable TornadoSettingState getState() {
        return this;
    }

    /**
     * Loads the settings state from the provided state object.
     * This is triggered when the IDE is started, or when the plugin is loaded.
     *
     * @param state The TornadoSetting object that represents the saved state.
     */
    @Override
    public void loadState(@NotNull TornadoSettingState state) {
        XmlSerializerUtil.copyBean(state,this);
    }
}
