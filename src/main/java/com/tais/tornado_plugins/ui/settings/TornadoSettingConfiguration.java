package com.tais.tornado_plugins.ui.settings;

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
        return "TornadoVM";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mySettingsComponent = new TornadoSettingsComponent();
        return mySettingsComponent.getPanel();
    }

    @Override
    public boolean isModified() {
        TornadoSettingState settings = TornadoSettingState.getInstance();
        boolean modified = !mySettingsComponent.getJava21Path().equals(settings.Java21);
        modified |= !mySettingsComponent.getTornadoEnvPath().equals(settings.TornadoRoot);
        modified |= mySettingsComponent.getMaxArraySize() != settings.parameterSize;
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
        settings.isValid = true;
        settings.TornadoRoot = mySettingsComponent.getTornadoEnvPath();
        settings.Java21 = mySettingsComponent.getJava21Path();
        settings.parameterSize = mySettingsComponent.getMaxArraySize();
    }

    //The method is invoked immediately after createComponent().
    @Override
    public void reset() {
        TornadoSettingState settings = TornadoSettingState.getInstance();
        mySettingsComponent.setTornadoEnvPath(settings.TornadoRoot);
        mySettingsComponent.setJava21Path(settings.Java21);
        mySettingsComponent.setMaxArraySize(settings.parameterSize);
    }

    @Override
    public void disposeUIResources() {
        mySettingsComponent = null;
    }
}
