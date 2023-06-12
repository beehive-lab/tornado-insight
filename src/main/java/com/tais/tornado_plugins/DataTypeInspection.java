package com.tais.tornado_plugins;

import com.google.gson.stream.JsonReader;
import com.intellij.codeInspection.*;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.util.*;
import java.io.InputStreamReader;

public class DataTypeInspection extends AbstractBaseJavaLocalInspectionTool {
    static List<String> supportedType;
    static {
        InputStream resource = DataTypeInspection.class.getClassLoader().getResourceAsStream("conf.json");
        assert resource != null;
        JsonReader reader = new JsonReader(new InputStreamReader(resource));
        Conf types = new Gson().fromJson(reader, Conf.class);
        supportedType = Arrays.asList(types.datatype);
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        HashSet<PsiVariable> reportedVariable = new HashSet<>();
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")){
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation,PsiMethod.class);
                    assert parent != null;
                    PsiParameter[] parameterList = parent.getParameterList().getParameters();
                    PsiCodeBlock method_body = parent.getBody();
                    PsiVariable[] variables_list = PsiTreeUtil.collectElementsOfType(method_body, PsiVariable.class).
                            toArray(new PsiVariable[0]);
                    for (PsiVariable var: variables_list){
                        if (!(var.getType() instanceof PsiPrimitiveType) &&
                                !(supportedType.contains(var.getType().toString().replace("PsiType:",""))) &&
                                !reportedVariable.contains(var)){
                                    reportedVariable.add(var);
                                    holder.registerProblem(
                                            var,
                                            "Unsupported datatype in TornadoVM.",
                                            ProblemHighlightType.ERROR
                                    );
                        }
                    }

                    for (PsiParameter parameter:parameterList){
                        if (!(parameter.getType() instanceof PsiPrimitiveType) &&
                            !(supportedType.contains(parameter.getType().toString().replace("PsiType:",""))) &&
                            !reportedVariable.contains(parameter)){
                            reportedVariable.add(parameter);
                            holder.registerProblem(
                                    parameter,
                                    "Unsupported datatype in TornadoVM.",
                                    ProblemHighlightType.ERROR
                            );
                        }
                    }


                }
            }
        };
    }
}

class Conf{
    String[] datatype;
}


