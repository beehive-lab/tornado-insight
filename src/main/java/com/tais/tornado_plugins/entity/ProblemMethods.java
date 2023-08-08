package com.tais.tornado_plugins.entity;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.PsiMethod;
import com.tais.tornado_plugins.message.RefreshListener;
import com.tais.tornado_plugins.message.TornadoTaskRefreshListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class ProblemMethods {
    private final CopyOnWriteArraySet<String> methodSet = new CopyOnWriteArraySet<>();
    private static final ProblemMethods instance = new ProblemMethods();

    private ProblemMethods() {}
    public static ProblemMethods getInstance() {
        return instance;
    }

    public void addMethod(PsiMethod method){
        if(methodSet.add(method.getText())){
            ProjectManager.getInstance().getOpenProjects()[0].getMessageBus().
                    syncPublisher(TornadoTaskRefreshListener.TOPIC).refresh();
        }
    }

    public CopyOnWriteArraySet<String> getMethodSet() {
        return methodSet;
    }

    public void clear(){
        methodSet.clear();
    }
}
