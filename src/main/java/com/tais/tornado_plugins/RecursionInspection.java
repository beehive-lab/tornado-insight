package com.tais.tornado_plugins;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

public class RecursionInspection extends AbstractBaseJavaLocalInspectionTool {
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")){
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation,PsiMethod.class);
                    assert parent != null;
                    parent.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitCallExpression(PsiCallExpression callExpression) {
                            super.visitCallExpression(callExpression);
                            PsiMethod calledMethod = callExpression.resolveMethod();
                            if (parent.equals(calledMethod)){
                                holder.registerProblem(
                                        calledMethod,
                                        "Recursive calls are not allowed in a method with @Reduce or @Parallel parameters",
                                        ProblemHighlightType.ERROR);
                            }
                        }
                    });
                }
            };
        };
    }
}
