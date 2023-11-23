package com.tais.tornado_plugins.util;

import com.intellij.psi.PsiMethod;

public class MethodUtil {
    public static String makePublicStatic(PsiMethod method){
        // Check if the method is already static or public
        String methodStr = method.getText();
        boolean isStatic = methodStr.contains("static");
        boolean isPrivate = methodStr.contains("private");

        // Replace or prepend modifiers as necessary
        if (!isStatic && isPrivate) {
            return methodStr.replaceFirst("\\b(\\w+\\s+\\w+\\()", "public static $1");
        } else if (!isStatic) {
            return methodStr.replaceFirst("public", "public static");
        } else if (isPrivate) {
            return methodStr.replaceFirst("private", "");
        }
        // If already static public, return the original string
        return methodStr;
    }
}
