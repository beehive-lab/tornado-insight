package com.tais.tornado_plugins;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;
public class ThrowInspection extends AbstractBaseJavaLocalInspectionTool{
    public @NotNull PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        HashSet<PsiThrowStatement> reportedStatement = new HashSet<>();
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
                        //Check if an exception is thrown in the function body
                        @Override
                        public void visitThrowStatement(PsiThrowStatement statement) {
                            super.visitThrowStatement(statement);
                            if (!reportedStatement.contains(statement)){
                                holder.registerProblem(statement,
                                        "TornadoVM dose not support for Traps/Exceptions",
                                        ProblemHighlightType.ERROR);
                                reportedStatement.add(statement);
                            }
                        }

                        //Check if the method body have try/catch code block
                        @Override
                        public void visitTryStatement(PsiTryStatement statement) {
                            super.visitTryStatement(statement);
                            holder.registerProblem(statement,
                                    "TornadoVM: TornadoVM dose not support for Traps/Exceptions." +
                                            "The code block in Catch will be ignored, and the exception " +
                                            "that may be thrown in Try block will not be handled",
                                    ProblemHighlightType.WARNING);
                        }
                    });
                    //Check if an exception is thrown in the function declaration
                    if(!reportedMethod.contains(parent)){
                        for(PsiClassType exception : parent.getThrowsList().getReferencedTypes()) {
                            holder.registerProblem(parent.getThrowsList(), "TornadoVM: Incompatible thrown types " +
                                            "Exception in functional expression\n " + exception.getCanonicalText(),
                                    ProblemHighlightType.ERROR);
                        }
                        reportedMethod.add(parent);
                    }
                }
            };
        };
    }
}
