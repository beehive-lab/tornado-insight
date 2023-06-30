package com.tais.tornado_plugins;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

public class StaticTaskGraphInspection extends AbstractBaseJavaLocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolve = expression.resolve();

                if (resolve instanceof PsiField field) {
                    PsiClass containingClass = field.getContainingClass();
                    PsiType type = field.getType();

                    if (containingClass != null && "TaskGraph".equals(type.getPresentableText())) {
                        if (field.hasModifierProperty(PsiModifier.STATIC)) {
                            holder.registerProblem(expression,
                                    "TornadoVM: TornadoVM currently does not support static TaskGraph and Tasks");
                        }
                    }
                }
            }

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiReferenceExpression methodExpression = expression.getMethodExpression();
                PsiElement resolve = methodExpression.resolve();

                if (resolve instanceof PsiMethod method) {
                    PsiClass containingClass = method.getContainingClass();

                    if (containingClass != null && "TaskGraph".equals(containingClass.getName()) && "task".equals(method.getName())) {
                        if (method.hasModifierProperty(PsiModifier.STATIC)) {
                            holder.registerProblem(expression,
                                    "TornadoVM: TornadoVM currently does not support static TaskGraph and Tasks");
                        }
                    }
                }
            }
        };

    }
}
