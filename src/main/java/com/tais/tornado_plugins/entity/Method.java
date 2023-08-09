package com.tais.tornado_plugins.entity;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;

import java.util.ArrayList;

public class Method {
    private final PsiMethod method;
    private final ArrayList<String> parameterValues;
    private ArrayList<PsiParameter> toDeviceParameters;
    private ArrayList<PsiParameter> toHostParameters;

    public static final int DEFAULT = 0;
    public static final int TransferToDevice = 1;
    public static final int TransferToHost = 2;


    public Method(PsiMethod method, ArrayList<String> parameterValues) {
        this.method = method;
        this.parameterValues = parameterValues;
        this.toDeviceParameters = new ArrayList<>();
        this.toHostParameters = new ArrayList<>();

    }

    public Method(PsiMethod method, ArrayList<String> parameterValues, ArrayList<PsiParameter> defaultParameters,
                  ArrayList<PsiParameter> toDeviceParameters, ArrayList<PsiParameter> toHostParameters) {
        this.method = method;
        this.parameterValues = parameterValues;
        this.toDeviceParameters = toDeviceParameters;
        this.toHostParameters = toHostParameters;
    }

    public PsiMethod getMethod() {
        return method;
    }

    public ArrayList<String> getParameterValues() {
        return parameterValues;
    }

    public ArrayList<PsiParameter> getToDeviceParameters() {
        return toDeviceParameters;
    }

    public ArrayList<PsiParameter> getToHostParameters() {
        return toHostParameters;
    }
}
