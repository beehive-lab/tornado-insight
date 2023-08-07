package com.tais.tornado_plugins.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.UI;
import com.tais.tornado_plugins.entity.TornadoSetting;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class TornadoSettingConfiguration implements Configurable {
    private final TextFieldWithBrowseButton fileChooser;
    private final JComponent component;
    private final static String title = "TornadoVM environment variable file:";

    public TornadoSettingConfiguration() {
        component = new JBPanel<>(new VerticalFlowLayout(VerticalFlowLayout.LEFT));

        fileChooser = new TextFieldWithBrowseButton();
        if (TornadoSetting.getInstance().setVarFile != null){
            fileChooser.setText(TornadoSetting.getInstance().setVarFile);
        }
        fileChooser.addBrowseFolderListener(title, "Choose the .sh file",
                ProjectManager.getInstance().getOpenProjects()[0],
                new FileChooserDescriptor(true, false, false, false, false, false) {
                    @Override
                    public boolean isFileSelectable(VirtualFile file) {
                        return file.getName().endsWith(".sh");
                    }
                });
        JPanel panel = UI.PanelFactory.panel(fileChooser).
                withComment("<p>The environment variable file for TornadoVM is usually \"TornadoVM/setvars.sh\". " +
                        "This file allows the plugin to call your host's TornadoVM for further analysis of Tornado methods.</p>")
                .createPanel();
        component.add(new JLabel(title));
        component.add(panel);
    }

    @Override
    public @NlsContexts.ConfigurableName String getDisplayName() {
        return "TornadoVM";
    }

    @Override
    public @Nullable JComponent createComponent() {
        return component;
    }

    @Override
    public boolean isModified() {
        return true;
    }

    @Override
    public void apply() throws ConfigurationException {
        //TODO: Validate file
        TornadoSetting.getInstance().setVarFile = fileChooser.getText();
        System.out.println(fileChooser.getText());
    }
}
