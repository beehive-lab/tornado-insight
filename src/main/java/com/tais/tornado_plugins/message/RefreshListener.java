package com.tais.tornado_plugins.message;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.util.messages.MessageBus;

import java.util.List;

public class RefreshListener{
    public static void init(MessageBus bus){
        bus.connect().subscribe(TornadoTaskRefreshListener.TOPIC, new TornadoTaskRefreshListener() {
            @Override
            public void refresh(List<PsiMethod> taskList, Project project) {
                System.out.println("Got!");
                System.out.println(taskList.toString());

            }
        });
    }
}
