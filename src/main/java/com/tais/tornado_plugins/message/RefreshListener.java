package com.tais.tornado_plugins.message;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.PsiMethod;
import com.intellij.util.messages.MessageBus;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;
import com.tais.tornado_plugins.ui.TornadoVM;
import com.tais.tornado_plugins.util.TornadoTWTask;

import java.util.List;

public class RefreshListener{
    public static void init(MessageBus bus){
        bus.connect().subscribe(TornadoTaskRefreshListener.TOPIC,
                (TornadoTaskRefreshListener) () ->
                        TornadoTWTask.addTask(ProjectManager.getInstance().getOpenProjects()[0],
                                TornadoToolsWindow.getListModel()));
    }
}
