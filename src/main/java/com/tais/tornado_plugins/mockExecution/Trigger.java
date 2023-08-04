package com.tais.tornado_plugins.mockExecution;

import com.intellij.analysis.problemsView.Problem;

import com.intellij.analysis.problemsView.ProblemsCollector;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNewExpression;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Objects;

public class Trigger extends AbstractBaseJavaLocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(PsiMethod method) {
                super.visitMethod(method);
                Project project = method.getProject();
                if (hasTornadoExecutionPlanInstantiation(method)) {
                    if (!hasCompileErrors(project)) {
                        /*System.out.println("Ready to do mock execution");*/
                    }
                }
            }

            private boolean hasCompileErrors(Project project){
                Logger LOG = Logger.getInstance(ProblemsHolder.class);
                int problemCount = ProblemsCollector.getInstance(project).getProblemCount();
                return problemCount != 0;
            }

            private boolean hasTornadoExecutionPlanInstantiation(PsiMethod method){
                final boolean[] match = {false};
                method.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitNewExpression(PsiNewExpression expression) {

                        if (expression!=null && expression.getClassReference()!=null) {
                            PsiElement resolvedClass = Objects.requireNonNull(expression.getClassReference()).resolve();
                            if (resolvedClass != null){
                                if (resolvedClass.toString().equals("PsiClass:TornadoExecutionPlan")){
                                    match[0] = true;
                                }
                            }
                        }
                    }
                });
                return match[0];
            }
        };
    }
}
