/*
 * Copyright (c) 2023, 2025-2026, APT Group, Department of Computer Science,
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

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.TestOnly;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;
import java.awt.*;

import java.util.Objects;

public class TornadoSettingsComponent {

    private final JPanel myMainPanel;

    private final JBTextField myMaxArraySize = new JBTextField(4);

    public TornadoSettingsComponent() {
        // Create warning banner if TORNADOVM_HOME is not set
        FormBuilder formBuilder = FormBuilder.createFormBuilder();
        if (!TornadoSdk.getInstance().isConfigured()) {
            JPanel warningPanel = createWarningPanel();
            formBuilder.addComponent(warningPanel);
        }

        JPanel dynamicInspectionPanel = FormBuilder.createFormBuilder().addLabeledComponent(new JBLabel(MessageBundle.message("ui.setting.label.size")), myMaxArraySize, 1)
                .addLabeledComponent(new JBLabel(" "), new JLabel("<html><div style='width:400px; color:gray;'>" + MessageBundle.message("ui.settings.max.array.size", String.valueOf(TornadoSettingState.MAX_PARAMETER_SIZE - 1)) + "</div></html>"))
                .getPanel();

        dynamicInspectionPanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.dynamic")));

        myMainPanel = formBuilder.addComponent(dynamicInspectionPanel).addComponentFillVertically(new JPanel(), 0).getPanel();
    }

    private JPanel createWarningPanel() {
        JPanel warningPanel = new JPanel(new BorderLayout());
        warningPanel.setBorder(JBUI.Borders.empty(10));

        JLabel iconLabel = new JLabel(UIManager.getIcon("OptionPane.warningIcon"));
        iconLabel.setBorder(JBUI.Borders.emptyRight(10));

        JPanel messagePanel = new JPanel();
        messagePanel.setLayout(new BoxLayout(messagePanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel(MessageBundle.message("ui.settings.sdkMissing.title"));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel instructionLabel = new JLabel(MessageBundle.message("ui.settings.sdkMissing.instruction"));
        instructionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel linkPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        linkPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        linkPanel.add(new JLabel(MessageBundle.message("ui.settings.sdkMissing.visit") + " "));
        LinkLabel<?> link = new LinkLabel<>(MessageBundle.message("ui.settings.sdkMissing.link"), null, (aSource, aLinkData) ->
            BrowserUtil.browse("https://www.tornadovm.org/downloads")
        );
        linkPanel.add(link);
        linkPanel.add(new JLabel(" " + MessageBundle.message("ui.settings.sdkMissing.instructions")));

        messagePanel.add(titleLabel);
        messagePanel.add(Box.createVerticalStrut(5));
        messagePanel.add(instructionLabel);
        messagePanel.add(Box.createVerticalStrut(5));
        messagePanel.add(linkPanel);

        warningPanel.add(iconLabel, BorderLayout.WEST);
        warningPanel.add(messagePanel, BorderLayout.CENTER);

        return warningPanel;
    }

    public JPanel getPanel() {
        return myMainPanel;
    }

    /**
     * Returns the max array size typed in the field. Never throws: empty, "0"
     * or unparsable input yields the default, because isModified() polls this
     * on every keystroke. isValidPath() is what reports invalid input on Apply.
     */
    public int getMaxArraySize() {
        String text = myMaxArraySize.getText().trim();
        if (text.isEmpty() || Objects.equals(text, "0")) {
            return TornadoSettingState.DEFAULT_PARAMETER_SIZE;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return TornadoSettingState.DEFAULT_PARAMETER_SIZE;
        }
    }

    public void setMaxArraySize(int size) {
        myMaxArraySize.setText(String.valueOf(size));
    }

    @TestOnly
    void setMaxArraySizeText(String text) {
        myMaxArraySize.setText(text);
    }

    public String isValidPath() {
        String parameterSize = myMaxArraySize.getText().trim();

        // Empty or "0" mean "use the default" - getMaxArraySize() substitutes 128 in
        // both cases, so the user can blank the field instead of typing a number.
        if (StringUtil.isEmpty(parameterSize) || Objects.equals(parameterSize, "0")) {
            return "";
        }
        try {
            int size = Integer.parseInt(parameterSize);
            if (size < 0 || size >= TornadoSettingState.MAX_PARAMETER_SIZE) {
                return MessageBundle.message("ui.settings.validation.invalidSize", String.valueOf(TornadoSettingState.MAX_PARAMETER_SIZE));
            }
        } catch (NumberFormatException e) {
            return MessageBundle.message("ui.settings.validation.invalidSize", String.valueOf(TornadoSettingState.MAX_PARAMETER_SIZE));
        }

        return "";
    }
}

