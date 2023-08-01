package com.tais.tornado_plugins.service;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.tais.tornado_plugins.ui.TornadoToolsWindow;
import com.tais.tornado_plugins.ui.TornadoVM;
import com.tais.tornado_plugins.util.TornadoTWTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class TWTasksButtonEvent {
    public void pressButton(){
        List selectedValuesList = TornadoToolsWindow.getToolsWindow().getTasksList().getSelectedValuesList();
        if (selectedValuesList.isEmpty()) System.out.println("None Selected");
        ArrayList<PsiMethod> methodList = TornadoTWTask.getMethods(selectedValuesList);

    }

    private void creatFile(List<PsiMethod> methodList, PsiFile file){

    }
}
