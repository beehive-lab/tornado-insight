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

package uk.ac.manchester.beehive.tornado.plugins.listener;

import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import uk.ac.manchester.beehive.tornado.plugins.message.TornadoTaskRefreshListener;
import org.jetbrains.annotations.NotNull;

public class EditorSwitch implements FileEditorManagerListener {

    //When editor selection changed, refresh the window tool
    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        //ProblemMethods.getInstance().clear();
        Project project = event.getManager().getProject();
        if (ToolWindowManager.getInstance(project).getToolWindow("TornadoVM") == null ||
            event.getNewFile() == null) return;


        TornadoTaskRefreshListener tornadoTaskRefreshListener =
                project.getMessageBus().syncPublisher(TornadoTaskRefreshListener.REFRESH_TOPIC);
        tornadoTaskRefreshListener.refresh(project,event.getNewFile());
    }

}
