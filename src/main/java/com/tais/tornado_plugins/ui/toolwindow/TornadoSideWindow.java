package com.tais.tornado_plugins.ui.toolwindow;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
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
import com.tais.tornado_plugins.message.TornadoTaskRefreshListener;
import com.tais.tornado_plugins.util.DataKeys;
import com.tais.tornado_plugins.util.MessageBundle;
import com.tais.tornado_plugins.util.TornadoTWTask;
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
        content = ContentFactory.getInstance().createContent(inspectorInfoPanel, "Inspectors Info", false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public void dispose() {
    }

    private static class ToolWindowContent extends SimpleToolWindowPanel{

        JBList<String> TornadoList = new JBList<>();

        public ToolWindowContent(Project project, ToolWindow toolWindow) {
            super(Boolean.TRUE, Boolean.TRUE);
            SimpleToolWindowPanel simpleToolWindowPanel = new SimpleToolWindowPanel(true, true);

            DefaultListModel<String> model = new DefaultListModel<>();
            TornadoList.setModel(model);
            TornadoList.getEmptyText().setText(MessageBundle.message("ui.toolwindow.defaultText"));
            simpleToolWindowPanel.add(TornadoList);
            setContent(simpleToolWindowPanel);


            ActionToolbar test = ActionManager.getInstance().createActionToolbar(
                    "TornadoInsight Toolbar",
                    (DefaultActionGroup) ActionManager.getInstance().getAction("tornado.bar"),
                    true);
            test.setTargetComponent(simpleToolWindowPanel);
            setToolbar(test.getComponent());
            init(project);
       }

        @Override
        public @Nullable Object getData(@NotNull @NonNls String dataId) {
            if (DataKeys.TORNADOINSIGHT_LIST_MODEL.is(dataId)){
                return getModel();
            }
            if (DataKeys.TORNADO_SELECTED_LIST.is(dataId)){
                return TornadoList.getSelectedValuesList();
            }
            if (DataKeys.TORNADO_LIST.is(dataId)){
                return TornadoList;
            }
            return null;
        }

        public DefaultListModel<String> getModel(){
            return (DefaultListModel<String>) TornadoList.getModel();
        }

        public void init(Project project){
            project.getMessageBus().connect().subscribe(
                    TornadoTaskRefreshListener.REFRESH_TOPIC,
                    new TornadoTaskRefreshListener() {
                        @Override
                        public void refresh() {
                            TornadoTWTask.refresh(project,
                                    Objects.requireNonNull(FileEditorManager.getInstance(project).getSelectedFiles()[0]),
                                    getModel());
                        }

                        @Override
                        public void refresh(Project project, VirtualFile newFile) {
                            TornadoTWTask.refresh(project, newFile, getModel());
                        }
                    }
            );
        }
    }

    private static class InspectorInfoPanel extends SimpleToolWindowPanel{

        JScrollPane scrollPane = new JBScrollPane(InspectorInfoKt.inspectorPane());

        public InspectorInfoPanel(ToolWindow toolWindow) {
            super(Boolean.TRUE, Boolean.TRUE);
            SimpleToolWindowPanel simpleToolWindowPanel = new SimpleToolWindowPanel(true, true);
            simpleToolWindowPanel.add(scrollPane);
            setContent(simpleToolWindowPanel);
        }
    }

}



