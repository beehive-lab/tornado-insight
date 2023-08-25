package com.tais.tornado_plugins.util;

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.ArrayList;

public class MethodCall {

    public static ArrayList<String> getMethodCall(PsiMethod method) {
        ArrayList<String> calls = new ArrayList<>();
        PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression.class).forEach(
                methodCall -> {
                    PsiMethod psiMethod = methodCall.resolveMethod();
                    if (psiMethod != null) {
                        System.out.println(methodCall.getContainingFile().getVirtualFile().getCanonicalPath());
                        calls.add(methodCall.getContainingFile().getVirtualFile().getCanonicalPath());
                    }
                }
        );
        return calls;
    }
}
