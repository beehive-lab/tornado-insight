package com.tais.tornado_plugins;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

public class GetAnnotation extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        StringBuilder result = new StringBuilder();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        assert psiFile != null;
        psiFile.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                if (annotation.getQualifiedName().endsWith("Parallel") || annotation.getQualifiedName().endsWith("Reduce")){
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation,PsiMethod.class);
                    PsiCodeBlock method_body = parent.getBody();
                    PsiVariable[] variables_list = PsiTreeUtil.collectElementsOfType(method_body, PsiVariable.class).
                            toArray(new PsiVariable[0]);
                    for (PsiVariable var: variables_list){
                        if (!(var.getType() instanceof PsiPrimitiveType))
                            result.append(var.getType()).append("<br>");
                    }
                }

            }
        });
        Notifications.Bus.notify(new Notification("Print", "Unsupported data type: ", result.toString(), NotificationType.ERROR), e.getProject());
    }
}
