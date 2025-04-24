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

package uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import uk.ac.manchester.beehive.tornado.plugins.message.TornadoTaskRefreshListener;
import uk.ac.manchester.beehive.tornado.plugins.util.DataKeys;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

public class TornadoSideWindow implements ToolWindowFactory, Disposable {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ToolWindowContent toolWindowContent = new ToolWindowContent(project, toolWindow);
        InspectorInfoPanel inspectorInfoPanel = new InspectorInfoPanel(toolWindow);
        Content content = ContentFactory.getInstance().createContent(toolWindowContent, "TornadoVM Tasks", false);
        toolWindow.getContentManager().addContent(content);
        content = ContentFactory.getInstance().createContent(inspectorInfoPanel, "Inspector Guide", false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public void dispose() {
    }

    private static class ToolWindowContent extends SimpleToolWindowPanel implements DataProvider {

        JBList<String> TornadoList = new JBList<>();

        public ToolWindowContent(Project project, ToolWindow toolWindow) {
            super(Boolean.TRUE, Boolean.TRUE);
            SimpleToolWindowPanel simpleToolWindowPanel = new SimpleToolWindowPanel(true, true);

            DefaultListModel<String> model = new DefaultListModel<>();
            TornadoList.setModel(model);
            TornadoList.getEmptyText().setText(MessageBundle.message("ui.toolwindow.defaultText"));
            simpleToolWindowPanel.add(TornadoList);
            setContent(simpleToolWindowPanel);

            ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("TornadoInsight Toolbar", (DefaultActionGroup) ActionManager.getInstance().getAction("tornado.bar"), true);
            toolbar.setTargetComponent(simpleToolWindowPanel);
            setToolbar(toolbar.getComponent());

            init(project);
        }

        public <T> @Nullable T getData(@NotNull DataKey<T> key) {
            if (key == DataKeys.TORNADOINSIGHT_LIST_MODEL) {
                return (T) getModel();
            }
            if (key == DataKeys.TORNADO_SELECTED_LIST) {
                return (T) TornadoList.getSelectedValuesList();
            }
            if (key == DataKeys.TORNADO_LIST) {
                return (T) TornadoList;
            }
            return null;
        }

        /**
         * @deprecated Use {@link #getData(DataKey)} instead.
         */
        @Deprecated
        @Override
        public @Nullable Object getData(@NotNull @NonNls String dataId) {
            if (DataKeys.TORNADOINSIGHT_LIST_MODEL.is(dataId)) {
                return getModel();
            }
            if (DataKeys.TORNADO_SELECTED_LIST.is(dataId)) {
                return TornadoList.getSelectedValuesList();
            }
            if (DataKeys.TORNADO_LIST.is(dataId)) {
                return TornadoList;
            }
            return null;
        }

        public DefaultListModel<String> getModel() {
            return (DefaultListModel<String>) TornadoList.getModel();
        }

        public void init(Project project) {
            project.getMessageBus().connect().subscribe(TornadoTaskRefreshListener.REFRESH_TOPIC, new TornadoTaskRefreshListener() {
                @Override
                public void refresh() {
                    VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
                    if (selectedFiles.length > 0) {
                        TornadoTWTask.refresh(project, selectedFiles[0], getModel());
                    } else {
                        // Optional: handle the case where no file is selected
                        System.out.println("No file selected in editor for Tornado refresh.");
                    }
                }

                @Override
                public void refresh(Project project, VirtualFile newFile) {
                    TornadoTWTask.refresh(project, newFile, getModel());
                }
            });
        }
    }

    private static class InspectorInfoPanel extends SimpleToolWindowPanel {

        JScrollPane scrollPane = new JBScrollPane(InspectorInfoKt.inspectorPane());

        public InspectorInfoPanel(ToolWindow toolWindow) {
            super(Boolean.TRUE, Boolean.TRUE);
            SimpleToolWindowPanel simpleToolWindowPanel = new SimpleToolWindowPanel(true, true);
            simpleToolWindowPanel.add(scrollPane);
            setContent(simpleToolWindowPanel);
        }
    }

}
