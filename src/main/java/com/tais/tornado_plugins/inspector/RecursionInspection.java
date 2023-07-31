package com.tais.tornado_plugins.inspector;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

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
                            Set<PsiMethod> visited = new HashSet<>();
                            if (isRecursive(calledMethod, visited)){
                                assert calledMethod != null;
                                holder.registerProblem(
                                        calledMethod,
                                        "TornadoVM: Recursive calls are not allowed in a method with @Reduce " +
                                                "or @Parallel parameters",
                                        ProblemHighlightType.ERROR);
                            }
                        }
                    });
                }
            };
        };
    }
    private boolean isRecursive(PsiMethod method, Set<PsiMethod> visited) {
        if (!visited.add(method)) {
            return true;
        }
        assert method != null;
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
                PsiMethod calledMethod = call.resolveMethod();
                if (calledMethod != null && isRecursive(calledMethod, visited)) {
                    return true;
                }
            }
        }
        visited.remove(method);
        return false;
    }
}
