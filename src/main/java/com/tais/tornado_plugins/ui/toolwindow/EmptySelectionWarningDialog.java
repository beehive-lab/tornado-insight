package com.tais.tornado_plugins.ui.toolwindow;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.tais.tornado_plugins.util.MessageBundle;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Represents a warning dialog displayed when no Tornado tasks are selected.
 * <p>
 * This dialog is used to inform users that they need to select at least one TornadoVM Task
 * before proceeding with a particular action.
 * </p>
 */
public class EmptySelectionWarningDialog extends DialogWrapper {

    /**
     * Constructs a new warning dialog for empty Tornado task selection.
     */
    public EmptySelectionWarningDialog() {
        super(true); // The 'true' parameter means the dialog is modal
        init(); // Initializes the dialog (part of the DialogWrapper)
    }

    /**
     * Creates and returns the central panel for this dialog.
     *
     * @return the central UI component of this dialog.
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        // Returns a label informing the user to select a TornadoVM Task
        return new JBLabel(MessageBundle.message("ui.dialog.emptySelection"));
    }
}
