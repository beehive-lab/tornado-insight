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

package uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
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
