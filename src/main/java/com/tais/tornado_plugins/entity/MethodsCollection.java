package com.tais.tornado_plugins.entity;

import java.util.ArrayList;

public class MethodsCollection {
    private ArrayList<Method> methodArrayList;

    public MethodsCollection(ArrayList<Method> methodArrayList) {
        this.methodArrayList = methodArrayList;
    }

    public MethodsCollection() {
        methodArrayList = new ArrayList<>();
    }

    public ArrayList<Method> getMethodArrayList() {
        return methodArrayList;
    }

    public void setMethodArrayList(ArrayList<Method> methodArrayList) {
        this.methodArrayList = methodArrayList;
    }

    public void addMethod(Method method) {
        this.methodArrayList.add(method);
    }
}
