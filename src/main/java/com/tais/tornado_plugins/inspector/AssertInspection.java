package com.tais.tornado_plugins.inspector;


import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssertStatement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.entity.ProblemMethods;
import com.tais.tornado_plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * This inspection tool identifies and highlights the usage of the 'assert' keyword
 * within methods that are annotated with either "@Parallel" or "@Reduce".
 *
 * <p>Methods that have either of these annotations are expected not to use assertions,
 * and any such use will be highlighted as an error in the IDE.</p>
 */
public class AssertInspection extends AbstractBaseJavaLocalInspectionTool {

    /**
     * Constructs and returns a PsiElementVisitor that checks Java annotations for
     * specific criteria and highlights problematic patterns.
     *
     * @param holder     Holds the problems identified during inspection.
     * @param isOnTheFly Indicates whether the inspection is being run on-the-fly
     *                   while the user is editing.
     * @return A visitor to traverse and inspect Java elements.
     */
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                super.visitAnnotation(annotation);
                // Check if the annotation is of type 'Parallel' or 'Reduce'
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    // Retrieve the method which has the annotation
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    assert parent != null;
                    // Visit all elements inside the method to check for assert statements
                    parent.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitAssertStatement(PsiAssertStatement statement) {
                            super.visitAssertStatement(statement);
                            // Add the parent method to a list of problematic methods
                            ProblemMethods.getInstance().addMethod(parent);
                            //register the assert statement as an error
                            holder.registerProblem(statement,
                                    MessageBundle.message("inspection.assert"),
                                    ProblemHighlightType.ERROR);
                        }
                    });
                }
            }
        };
    }
}
