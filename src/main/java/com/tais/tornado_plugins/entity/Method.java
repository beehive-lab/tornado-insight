package com.tais.tornado_plugins.entity;

import com.intellij.psi.PsiMethod;

import java.util.ArrayList;

public class Method {
    PsiMethod method;
    ArrayList<String> parameterValues;

    public Method(PsiMethod method, ArrayList<String> parameterValues) {
        this.method = method;
        this.parameterValues = parameterValues;
    }

    public PsiMethod getMethod() {
        return method;
    }

    public ArrayList<String> getParameterValues() {
        return parameterValues;
    }
}
