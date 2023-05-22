package com.tais.tornado_plugins;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;

public class TornadoDataType {
    HashSet<String> datatype = new HashSet<>();
    private static TornadoDataType instance = new TornadoDataType();
    private TornadoDataType(){}

    public static void load(){

    }

    public static TornadoDataType getInstance(){
        return instance;
    }
}
