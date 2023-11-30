package com.tais.tornado_plugins.entity;

public enum RestrictedClasses {
    JAVA_LANG_SYSTEM("java.lang.System"),
    JAVA_LANG_RUNTIME("java.lang.Runtime"),
    JAVA_LANG_PROCESS("java.lang.Process"),
    JAVA_LANG_PROCESSBUILDER("java.lang.ProcessBuilder"),
    JAVA_LANG_THREAD("java.lang.Thread"),
    JAVA_IO("java.io"),
    JAVA_UTIL_CONCURRENT("java.util.concurrent"),
    JAVA_LANG_REFLECT("java.lang.reflect"),
    JAVA_NET("java.net"),
    JAVA_NIO("java.nio"),
    JAVA_SECURITY("java.security"),
    JAVA_SQL("java.sql");

    private final String className;

    RestrictedClasses(String className) {
        this.className = className;
    }

    public String getClassName() {
        return className;
    }

    public static boolean isRestrictedClass(String className) {
        for (RestrictedClasses restrictedClass : values()) {
            if (className.startsWith(restrictedClass.getClassName())) {
                return true;
            }
        }
        return false;
    }
}

