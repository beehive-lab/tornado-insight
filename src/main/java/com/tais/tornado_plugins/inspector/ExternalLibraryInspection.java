package com.tais.tornado_plugins.inspector;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.entity.ProblemMethods;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class ExternalLibraryInspection extends AbstractBaseJavaLocalInspectionTool {
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod method = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    assert method != null;
                    method.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                            super.visitMethodCallExpression(expression);
                            if (expression.getMethodExpression().resolve() != null) {
                                PsiMethod calledMethod = (PsiMethod) expression.getMethodExpression().resolve();
                                if (calledMethod != null){
                                    String qualifiedName = Objects.requireNonNull(calledMethod.getContainingClass()).getQualifiedName();
                                    if (qualifiedName != null && !qualifiedName.startsWith("java.") &&
                                            !qualifiedName.startsWith("uk.ac.manchester.tornado.api")&&
                                            !qualifiedName.startsWith("_Dummy_")) {
                                        ProblemMethods.getInstance().addMethod(method);
                                        holder.registerProblem(expression,
                                                "TornadoInsight is currently unable to check for " +
                                                        "non-JDK method calls",
                                                ProblemHighlightType.WARNING);
                                    }
                                }
                            }
                        }
                    });

                }
            }
        };
    }
}
