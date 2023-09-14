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
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.entity.ProblemMethods;
import com.tais.tornado_plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A custom inspection tool to check for invocations of potentially problematic system and external methods
 * within methods annotated with either "Parallel" or "Reduce".
 * <p>
 * This inspection tool searches for:
 * - Calls to native methods
 * - Calls to methods from various Java system and utility classes that may lead to unexpected behavior
 *   or resource usage in parallel or reduced contexts.
 * </p>
 */
public class SystemCallInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Builds the visitor used for the inspection.
     *
     * @param holder The container which receives the problems found during the inspection.
     * @param isOnTheFly Whether this inspection is being run on-the-fly, as the user types, or as a batch process.
     * @return The visitor instance for analyzing code constructs.
     */
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    assert parent != null;
                    parent.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                            super.visitMethodCallExpression(expression);
                            PsiMethod method = expression.resolveMethod();
                            if (method != null && method.hasModifierProperty(PsiModifier.NATIVE)) {
                                // This method call is invoking a native method.
                                // Handle or report as necessary.
                                ProblemMethods.getInstance().addMethod(parent);
                                holder.registerProblem(expression,
                                        MessageBundle.message("inspection.nativeCall"),
                                        ProblemHighlightType.ERROR);
                            }
                            if (method == null) return;
                            String className = Objects.requireNonNull(method.getContainingClass()).getQualifiedName();

                            // Checking for method calls from potentially problematic system and utility classes.
                            assert className != null;
                            if (className.startsWith("java.lang.System")||
                                    className.startsWith("java.lang.Runtime")||
                                    className.startsWith("java.lang.Process")||
                                    className.startsWith("java.lang.ProcessBuilder")||
                                    className.startsWith("java.lang.Thread")||
                                    className.startsWith("java.io")||
                                    className.startsWith("java.util.concurrent")||
                                    className.startsWith("java.lang.reflect")||
                                    className.startsWith("java.net")||
                                    className.startsWith("java.nio")||
                                    className.startsWith("java.security")||
                                    className.startsWith("java.sql")) {
                                ProblemMethods.getInstance().addMethod(parent);
                                holder.registerProblem(expression,
                                        MessageBundle.message("inspection.external"),
                                        ProblemHighlightType.ERROR);
                            }

                        }
                    });
                }
            }
        };
    }
}
