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

package uk.ac.manchester.beehive.tornado.plugins.ui.console;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Tool-window panel that embeds the project's TornadoInsight console. The
 * {@link ConsoleView} is owned (and disposed) by
 * {@link uk.ac.manchester.beehive.tornado.plugins.util.MessageUtils}.
 */
public class ConsolePanel extends SimpleToolWindowPanel {

    public ConsolePanel(@NotNull ConsoleView consoleView) {
        super(Boolean.FALSE, Boolean.TRUE);
        SimpleToolWindowPanel toolWindowPanel = new SimpleToolWindowPanel(Boolean.FALSE, Boolean.TRUE);
        toolWindowPanel.setContent(consoleView.getComponent());
        setContent(toolWindowPanel);
        final DefaultActionGroup consoleGroup = new DefaultActionGroup(consoleView.createConsoleActions());
        ActionToolbar consoleToolbar = ActionManager.getInstance().createActionToolbar("ConsoleToolbar", consoleGroup, true);
        consoleToolbar.setTargetComponent(toolWindowPanel);
        setToolbar(consoleToolbar.getComponent());
    }
}
