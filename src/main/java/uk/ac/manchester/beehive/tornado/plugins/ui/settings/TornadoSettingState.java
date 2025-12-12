/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.ui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.entity.EnvironmentVariable;

import java.io.File;
import java.io.FilenameFilter;

/**
 * Represents the settings associated with the TornadoVM plugin, allowing
 * for persistent storage and retrieval of configuration details.
 * The settings are persisted across IDE sessions and stored in 'tornado.xml'.
 */
@State(name = "TornadoVMSettingsState", storages = {@Storage(value = "TornadoSettings.xml")})
public class TornadoSettingState implements PersistentStateComponent<TornadoSettingState> {

    public boolean bytecodeVisualizerEnabled;
    public int parameterSize;
    public String tensorShapeDimensions;
    public boolean isValid;
    public boolean saveFileEnabled;
    public String debugFileSaveLocation;
    public String bytecodesFileSaveLocation;

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

    /**
     * Derives the TornadoVM root directory from TORNADO_SDK environment variable.
     * TORNADO_SDK typically points to {TORNADO_ROOT}/bin/sdk
     */
    private String getTornadoRoot() {
        String tornadoSdk = EnvironmentVariable.getTornadoSdk();
        if (tornadoSdk == null) {
            return null;
        }
        // TORNADO_SDK is typically {TORNADO_ROOT}/bin/sdk
        // So we need to go up two levels
        return tornadoSdk.replaceAll("/bin/sdk/?$", "");
    }

    public String setVarsPath(){
        String root = getTornadoRoot();
        return root != null ? root + "/setvars.sh" : null;
    }

    /**
     * Finds a JAR file in the TORNADO_SDK directory that matches the given prefix.
     * Returns the first matching JAR file path, or null if not found.
     */
    private String findTornadoJar(String jarPrefix) {
        String tornadoSdk = EnvironmentVariable.getTornadoSdk();
        if (tornadoSdk == null || tornadoSdk.isEmpty()) {
            return null;
        }

        // Resolve the absolute path in case TORNADO_SDK is relative
        File tornadoSdkFile = new File(tornadoSdk);
        if (!tornadoSdkFile.isAbsolute()) {
            // Try to resolve it relative to user home
            String userHome = System.getProperty("user.home");
            tornadoSdkFile = new File(userHome, tornadoSdk);
        }

        File tornadoJarDir = new File(tornadoSdkFile, "share/java/tornado");

        if (!tornadoJarDir.exists() || !tornadoJarDir.isDirectory()) {
            return null;
        }

        File[] matchingFiles = tornadoJarDir.listFiles((dir, name) -> name.startsWith(jarPrefix) && name.endsWith(".jar"));

        if (matchingFiles != null && matchingFiles.length > 0) {
            return matchingFiles[0].getAbsolutePath();
        }

        return null;
    }

    public String getMatricesPath(){
        return findTornadoJar("tornado-matrices");
    }

    public String getApiPath(){
        return findTornadoJar("tornado-api");
    }

    public String getUnitTestPath(){
        return findTornadoJar("tornado-unittests");
    }
}
