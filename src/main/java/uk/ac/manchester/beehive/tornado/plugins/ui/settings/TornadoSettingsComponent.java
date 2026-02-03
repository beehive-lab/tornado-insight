/*
 * Copyright (c) 2023, 2025, APT Group, Department of Computer Science,
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

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import java.awt.*;

import java.io.File;
import java.util.Objects;

public class TornadoSettingsComponent {

    private final JPanel myMainPanel;

    private final JCheckBox bytecodeVisualizerCheckbox = new JCheckBox("Use the TornadoVM Bytecode Visualizer Tool");

    private final JCheckBox saveFileCheckbox = new JCheckBox("Save Internal Debug File (For Developer Use Only)");

    private final TextFieldWithBrowseButton debugFileSaveLocationField = new TextFieldWithBrowseButton();

    private final TextFieldWithBrowseButton bytecodesFileSaveLocationField = new TextFieldWithBrowseButton();

    private final JBTextField myMaxArraySize = new JBTextField(4);

    public TornadoSettingsComponent() {
        attachFolderChooser(debugFileSaveLocationField, "Save Location for Generated Code", "Choose the folder you want generated codes to be saved");
        attachFolderChooser(bytecodesFileSaveLocationField, "Save Location for TornadoVM Bytecodes", "Choose the folder you want the TornadoVM Bytecodes to be saved");

        bytecodeVisualizerCheckbox.setSelected(false);
        saveFileCheckbox.setSelected(false);

        // Create warning banner if TORNADOVM_HOME is not set
        FormBuilder formBuilder = FormBuilder.createFormBuilder();
        String tornadoVmHome = System.getenv("TORNADOVM_HOME");
        if (tornadoVmHome == null || tornadoVmHome.isEmpty()) {
            JPanel warningPanel = createWarningPanel();
            formBuilder.addComponent(warningPanel);
        }

        JPanel bytecodesVisualizerPanel = FormBuilder.createFormBuilder().addComponent(bytecodeVisualizerCheckbox)
                .addLabeledComponent(new JBLabel(" "), new JLabel("<html><div style='width:400px; color:gray;'>" + MessageBundle.message("ui.settings.comment.visualizer.file") + "</div></html>"))
                .addLabeledComponent(new JBLabel("Save Location:"), bytecodesFileSaveLocationField).getPanel();
        bytecodesVisualizerPanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.visualizer")));

        JPanel dynamicInspectionPanel = FormBuilder.createFormBuilder().addLabeledComponent(new JBLabel("Max array size:"), myMaxArraySize, 1)
                .addLabeledComponent(new JBLabel(" "), new JLabel("<html><div style='width:400px; color:gray;'>" + MessageBundle.message("ui.settings.max.array.size") + "</div></html>"))
                .getPanel();

        dynamicInspectionPanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.dynamic")));

        JPanel debugPanel = FormBuilder.createFormBuilder().addComponent(saveFileCheckbox)
                .addLabeledComponent(new JBLabel(" "), new JLabel("<html><div style='width:400px; color:gray;'>" + MessageBundle.message("ui.settings.comment.debug.file") + "</div></html>"))
                .addLabeledComponent(new JBLabel("Save Location:"), debugFileSaveLocationField).getPanel();

        debugPanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.debugging")));

        myMainPanel = formBuilder.addComponent(bytecodesVisualizerPanel).addComponent(dynamicInspectionPanel).addComponent(debugPanel).addComponentFillVertically(new JPanel(), 0).getPanel();
    }

    private JPanel createWarningPanel() {
        JPanel warningPanel = new JPanel(new BorderLayout());
        warningPanel.setBorder(JBUI.Borders.empty(10));

        JLabel iconLabel = new JLabel(UIManager.getIcon("OptionPane.warningIcon"));
        iconLabel.setBorder(JBUI.Borders.emptyRight(10));

        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("<html><b>TORNADOVM_HOME environment variable is not set</b></html>");
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel instructionLabel = new JLabel("Please set the TORNADOVM_HOME environment variable and restart IntelliJ IDEA.");
        instructionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel linkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        linkPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkPanel.add(new JLabel("Visit "));
        LinkLabel<?> link = new LinkLabel<>("tornadovm.org/downloads", null, (aSource, aLinkData) ->
            BrowserUtil.browse("https://www.tornadovm.org/downloads")
        );
        linkPanel.add(link);
        linkPanel.add(new JLabel(" for installation instructions."));

        messagePanel.add(titleLabel);
        messagePanel.add(Box.createVerticalStrut(5));
        messagePanel.add(instructionLabel);
        messagePanel.add(Box.createVerticalStrut(5));
        messagePanel.add(linkPanel);

        warningPanel.add(iconLabel, BorderLayout.WEST);
        warningPanel.add(messagePanel, BorderLayout.CENTER);

        return warningPanel;
    }

    private void attachFolderChooser(TextFieldWithBrowseButton field, String title, String description) {
        field.addActionListener(e -> {
            FileChooserDescriptor descriptor = new FileChooserDescriptor(false, true, false, false, false, false);
            descriptor.setTitle(title);
            descriptor.setDescription(description);

            FileChooser.chooseFile(descriptor, null, null, null, file -> {
                if (file != null) {
                    field.setText(file.getPath());
                }
            });
        });
    }

    public JPanel getPanel() {
        return myMainPanel;
    }

    public boolean isBytecodeVisualizerEnabled() {
        return bytecodeVisualizerCheckbox.isSelected();
    }

    public void setBytecodeVisualizerEnabled(boolean enabled) {
        bytecodeVisualizerCheckbox.setSelected(enabled);
    }

    public int getMaxArraySize() {
        if (myMaxArraySize.getText().isEmpty() || Objects.equals(myMaxArraySize.getText(), "0")) {
            return 32;
        }
        return Integer.parseInt(myMaxArraySize.getText());
    }

    public void setMaxArraySize(int size) {
        myMaxArraySize.setText(String.valueOf(size));
    }

    public boolean isSaveFileEnabled() {
        return saveFileCheckbox.isSelected();
    }

    public void setSaveFileEnabled(boolean enabled) {
        saveFileCheckbox.setSelected(enabled);
    }

    public String getDebugFileSaveLocation() {
        return debugFileSaveLocationField.getText();
    }

    public void setDebugFileSaveLocation(String path) {
        debugFileSaveLocationField.setText(path);
    }

    public String getBytecodesFileSaveLocation() {
        return bytecodesFileSaveLocationField.getText();
    }

    public void setBytecodesFileSaveLocation(String path) {
        bytecodesFileSaveLocationField.setText(path);
    }

    public String isValidPath() {
        String parameterSize = myMaxArraySize.getText();
        if (isSaveFileEnabled()) {
            String saveLocation = debugFileSaveLocationField.getText();
            if (saveLocation.isEmpty()) {
                return MessageBundle.message("ui.settings.validation.emptySave");
            }
            File saveDir = new File(saveLocation);
            if (!saveDir.exists() || !saveDir.isDirectory() || !saveDir.canWrite()) {
                return MessageBundle.message("ui.settings.validation.invalidSave");
            }
        }

        if (StringUtil.isEmpty(parameterSize)) {
            return MessageBundle.message("ui.settings.validation.emptySize");
        }
        try {
            int size = Integer.parseInt(parameterSize);
            if (size >= 16384 || size <= 0) {
                return MessageBundle.message("ui.settings.validation.invalidSize");
            }
        } catch (NumberFormatException e) {
            return MessageBundle.message("ui.settings.validation.invalidSize");
        }

        return "";
    }
}

