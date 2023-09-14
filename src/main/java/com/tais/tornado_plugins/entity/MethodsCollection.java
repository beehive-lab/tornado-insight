package com.tais.tornado_plugins.entity;

import java.util.ArrayList;

/**
 * Represents a collection of {@link Method} objects. Provides mechanisms
 * to manipulate and access the underlying collection of methods.
 */

public class MethodsCollection {

    // The underlying collection of Method objects.
    private final ArrayList<Method> methodArrayList;

    /**
     * Default constructor that initializes the collection as an empty list.
     */
    public MethodsCollection() {
        methodArrayList = new ArrayList<>();
    }

    /**
     * Provides access to the underlying collection of methods.
     *
     * @return an ArrayList containing Method objects.
     */
    public ArrayList<Method> getMethodArrayList() {
        return methodArrayList;
    }

    /**
     * Adds a single {@link Method} object to the collection.
     *
     * @param method The Method object to be added.
     */
    public void addMethod(Method method) {
        this.methodArrayList.add(method);
    }
}
