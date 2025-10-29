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

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

/**
 * The instantiation of a generic Configurable implementation is documented in the interface file.
 * A few high-level points are reviewed here:
 *  - The Configurable.reset() method is invoked immediately after Configurable.createComponent().
 *    Initialization of Setting values in the constructor or createComponent() is unnecessary.
 *  - Once instantiated, a Configurable instance's lifetime continues regardless of whether
 *    the implementation's Settings are changed, or the user chooses a different entry on the Settings Dialog menu.
 *  - A Configurable instance's lifetime ends when OK or Cancel is selected in the Settings Dialog.
 *    An instance's Configurable.disposeUIResources() is called when the Settings Dialog is closing.
 */
public class TornadoSettingConfiguration implements Configurable {
    private TornadoSettingsComponent mySettingsComponent;

    @Nls(capitalization =  Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "TornadoInsight";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mySettingsComponent = new TornadoSettingsComponent();
        return mySettingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        TornadoSettingState settings = TornadoSettingState.getInstance();
        if (mySettingsComponent.getJdk() == null) return true;
        boolean modified = !mySettingsComponent.getJdk().equals(settings.JdkPath);
        modified |= !mySettingsComponent.getTornadoEnvPath().equals(settings.TornadoRoot);
        modified |= mySettingsComponent.isBytecodeVisualizerEnabled() != settings.bytecodeVisualizerEnabled;
        modified |= mySettingsComponent.getMaxArraySize() != settings.parameterSize;
        modified |= mySettingsComponent.getTensorShapeDimensions() != settings.tensorShapeDimensions;
        modified |= mySettingsComponent.isSaveFileEnabled() != settings.saveFileEnabled;
        modified |= !mySettingsComponent.getDebugFileSaveLocation().equals(settings.debugFileSaveLocation);
        modified |= !mySettingsComponent.getBytecodesFileSaveLocation().equals(settings.bytecodesFileSaveLocation);
        return modified;
    }

    @Override
    public void apply() throws ConfigurationException {
        TornadoSettingState settings = TornadoSettingState.getInstance();
        String error = mySettingsComponent.isValidPath();
        if (!Objects.equals(error, "")){
            settings.isValid = false;
            throw new ConfigurationException(error);
        }
        settings.TornadoRoot = mySettingsComponent.getTornadoEnvPath();
        settings.JdkPath = mySettingsComponent.getJdk();
        settings.bytecodeVisualizerEnabled = mySettingsComponent.isBytecodeVisualizerEnabled();
        settings.parameterSize = mySettingsComponent.getMaxArraySize();
        settings.tensorShapeDimensions = mySettingsComponent.getTensorShapeDimensions();
        settings.saveFileEnabled = mySettingsComponent.isSaveFileEnabled();
        settings.debugFileSaveLocation = mySettingsComponent.getDebugFileSaveLocation();
        settings.bytecodesFileSaveLocation = mySettingsComponent.getBytecodesFileSaveLocation();
    }

    //The method is invoked immediately after createComponent().
    @Override
    public void reset() {
        TornadoSettingState settings = TornadoSettingState.getInstance();
        mySettingsComponent.setTornadoEnvPath(settings.TornadoRoot);
        mySettingsComponent.setMyJdk(settings.JdkPath);
        mySettingsComponent.setBytecodeVisualizerEnabled(settings.bytecodeVisualizerEnabled);
        mySettingsComponent.setMaxArraySize(settings.parameterSize);
        mySettingsComponent.setTensorShapeDimensions(settings.tensorShapeDimensions);
        mySettingsComponent.setSaveFileEnabled(settings.saveFileEnabled);
        mySettingsComponent.setDebugFileSaveLocation(settings.debugFileSaveLocation);
        mySettingsComponent.setBytecodesFileSaveLocation(settings.bytecodesFileSaveLocation);
    }

    @Override
    public void disposeUIResources() {
        mySettingsComponent = null;
    }
}
