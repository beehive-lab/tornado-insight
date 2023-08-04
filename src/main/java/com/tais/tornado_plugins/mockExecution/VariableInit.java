package com.tais.tornado_plugins.mockExecution;

import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.tais.tornado_plugins.entity.Method;
import org.jetbrains.annotations.NotNull;

public class VariableInit {

    public static String variableInitHelper(@NotNull Method method){
        int size = method.getParameterValues().size();
        StringBuilder returnString = new StringBuilder();
        for (int i=0; i < size; i++){
            String parameterPrefix = method.getMethod().getParameterList().getParameters()[i].getText();
            String value = method.getParameterValues().get(i);
            returnString.append(parameterPrefix).append("=").append(value).append(";").append("\n");
        }
        return returnString.toString();
    }
}
