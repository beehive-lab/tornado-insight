package com.tais.tornado_plugins.inspector;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.tais.tornado_plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

public class StaticTaskGraphInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Constructs and returns a `PsiElementVisitor` that inspects Java code
     * for the problematic static usages of `TaskGraph`.
     *
     * @param holder     Collects problems detected during inspection.
     * @param isOnTheFly Indicates whether the inspection is done on-the-fly, i.e., as you type.
     * @return A visitor to inspect Java code elements.
     */
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolve = expression.resolve();
                // Check if the resolved reference is a field and has the type "TaskGraph"
                if (resolve instanceof PsiField field) {
                    PsiClass containingClass = field.getContainingClass();
                    PsiType type = field.getType();
                    // Ensure that the field is of type "TaskGraph" and is static
                    if (containingClass != null && "uk.ac.manchester.tornado.api.TaskGraph".equals(type.getCanonicalText())) {
                        // If so, register a problem since static TaskGraph is not desired.
                        if (field.hasModifierProperty(PsiModifier.STATIC)) {
                            holder.registerProblem(expression,
                                    MessageBundle.message("inspection.staticTaskGraph"), ProblemHighlightType.ERROR);
                        }
                    }
                }
            }

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);
                PsiReferenceExpression methodExpression = expression.getMethodExpression();
                PsiElement resolve = methodExpression.resolve();
                // Check if the method being called belongs to the "TaskGraph" class and has the name "task"
                if (resolve instanceof PsiMethod method) {
                    PsiClass containingClass = method.getContainingClass();
                    // Ensure that the method belongs to "TaskGraph" and has the name "task" and is static
                    if (containingClass != null
                            && "uk.ac.manchester.tornado.api.TaskGraph".equals(containingClass.getQualifiedName())
                            && "task".equals(method.getName())) {
                        // If so, register a problem since static task method in TaskGraph is not desired.
                        if (method.hasModifierProperty(PsiModifier.STATIC)) {
                            holder.registerProblem(expression,
                                    MessageBundle.message("inspection.staticTaskGraph"),ProblemHighlightType.ERROR);
                        }
                    }
                }
            }
        };
    }
}
