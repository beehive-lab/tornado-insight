package com.tais.tornado_plugins.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class EmptySelectionWarningDialog extends DialogWrapper {

    public EmptySelectionWarningDialog() {
        super(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return new JBLabel("Please select at least one Tornado task!");
    }
}
