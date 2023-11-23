package com.tais.tornado_plugins.inspector;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.entity.ProblemMethods;
import com.tais.tornado_plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;

/**
 * A custom inspection tool to check for thrown exceptions within methods annotated with
 * "Parallel" or "Reduce" as TornadoVM does not support for traps/exceptions.
 * <p>
 * This inspection tool ensures that:
 * - No exceptions are thrown within the method body.
 * - The method body does not contain any try/catch blocks.
 * - No exceptions are declared to be thrown in the method's signature.
 * </p>
 */
public class ThrowInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Builds the visitor used for the inspection.
     *
     * @param holder The container which receives the problems found during the inspection.
     * @param isOnTheFly Whether this inspection is being run on-the-fly, as the user types, or as a batch process.
     * @return The visitor instance for analyzing code constructs.
     */
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        HashSet<PsiThrowStatement> reportedStatement = new HashSet<>();
        HashSet<PsiMethod> reportedMethod = new HashSet<>();
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    if (parent == null) return;
                    parent.accept(new JavaRecursiveElementVisitor() {
                        //Check if an exception is thrown in the function body
                        @Override
                        public void visitThrowStatement(PsiThrowStatement statement) {
                            super.visitThrowStatement(statement);
                            if (!reportedStatement.contains(statement)) {
                                ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), parent);
                                holder.registerProblem(statement,
                                        MessageBundle.message("inspection.traps.throw"),
                                        ProblemHighlightType.ERROR);
                                reportedStatement.add(statement);
                            }
                        }

                        //Check if the method body have try/catch code block
                        @Override
                        public void visitTryStatement(PsiTryStatement statement) {
                            super.visitTryStatement(statement);
                            ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), parent);
                            holder.registerProblem(statement,
                                    MessageBundle.message("inspection.traps.tryCatch"),
                                    ProblemHighlightType.ERROR);
                        }
                    });
                    // Checking the method signature for thrown exceptions
                    if (!reportedMethod.contains(parent)) {
                        for (PsiClassType exception : parent.getThrowsList().getReferencedTypes()) {
                            holder.registerProblem(parent.getThrowsList(),
                                    MessageBundle.message("inspection.traps.throws")+ "\n" + exception.getCanonicalText(),
                                    ProblemHighlightType.ERROR);
                            ProblemMethods.getInstance().addMethod(holder.getProject(), holder.getFile(), parent);
                        }
                        reportedMethod.add(parent);
                    }
                }
            }
        };
    }
}
