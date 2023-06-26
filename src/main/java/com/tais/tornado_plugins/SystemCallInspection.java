package com.tais.tornado_plugins;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;

public class SystemCallInspection extends AbstractBaseJavaLocalInspectionTool {
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        HashSet<PsiMethod> reportedMethod = new HashSet<>();
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")){
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation,PsiMethod.class);
                    assert parent != null;
                    parent.accept(new JavaRecursiveElementVisitor() {
                        public void visitReferenceExpression(PsiReferenceExpression expression) {
                            super.visitReferenceExpression(expression);
                            PsiElement resolved = expression.resolve();
                            if (resolved instanceof PsiField) {
                                PsiField field = (PsiField) resolved;
                                PsiClass containingClass = field.getContainingClass();
                                if (containingClass != null && "java.lang.System".equals(containingClass.getQualifiedName())) {
                                    holder.registerProblem(expression,
                                            "TornadoVM: TornadoVM does not support System class",
                                            ProblemHighlightType.ERROR);
                                }
                            }
                        }
                    });
                }
            };
        };
    }
}
