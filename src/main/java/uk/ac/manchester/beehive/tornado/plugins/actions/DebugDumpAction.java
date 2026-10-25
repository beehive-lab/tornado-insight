/*
 * Copyright (c) 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.settings.TornadoSettingState;

/**
 * Toolbar toggle for the internal debug dump: when on, dynamic inspection keeps the generated
 * tests and kernels of each run in the system temporary folder. Shown only in internal mode
 * ({@code -Didea.is.internal=true}, which {@code ./gradlew runIde} sets).
 */
public class DebugDumpAction extends ToggleAction implements DumbAware {

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return TornadoSettingState.getInstance().debugDumpEnabled;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        TornadoSettingState.getInstance().debugDumpEnabled = state;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        super.update(e);
        e.getPresentation().setEnabledAndVisible(ApplicationManager.getApplication().isInternal());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
