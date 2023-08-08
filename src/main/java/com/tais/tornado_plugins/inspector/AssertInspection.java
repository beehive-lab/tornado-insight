package com.tais.tornado_plugins.inspector;


import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAssertStatement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.siyeh.ig.j2me.ArrayLengthInLoopConditionInspection;
import com.tais.tornado_plugins.entity.ProblemMethods;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Objects;

public class AssertInspection extends AbstractBaseJavaLocalInspectionTool {
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
                        public void visitAssertStatement(PsiAssertStatement statement) {
                            super.visitAssertStatement(statement);
                            ProblemMethods.getInstance().addMethod(parent);
                            holder.registerProblem(statement,
                                    "TornadoVM: Assert statement is not supported",
                                    ProblemHighlightType.ERROR);
                        }
                    });
                }
            };
//
//            @Override
//            public void visitFile(@NotNull PsiFile file) {
//                super.visitFile(file);
//                ProblemMethods.getInstance().addMethod(AssertInspection.this, methods);
//                methods.clear();
//            }
        };
    }
}
