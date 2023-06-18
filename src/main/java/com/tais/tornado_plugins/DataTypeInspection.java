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
                    parent.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitLocalVariable(PsiLocalVariable variable) {
                            checkVariable(variable.getType(),variable);
                        }

                        @Override
                        public void visitField(PsiField field) {
                            checkVariable(field.getType(),field);
                        }

                        @Override
                        public void visitParameter(PsiParameter parameter) {
                            checkVariable(parameter.getType(),parameter);
                        }

                        private void checkVariable(PsiType type, PsiVariable variable){
                            if (!type.equalsToText("int") && !type.equalsToText("boolean") && !type.equalsToText("double")
                                    && !type.equalsToText("long") && !type.equalsToText("char") && !type.equalsToText("float")
                                    && !type.equalsToText("byte") && !type.equalsToText("short")
                                    && !type.getCanonicalText().startsWith("int[]") && !type.getCanonicalText().startsWith("boolean[]")
                                    && !type.getCanonicalText().startsWith("double[]") && !type.getCanonicalText().startsWith("long[]")
                                    && !type.getCanonicalText().startsWith("char[]") && !type.getCanonicalText().startsWith("float[]")
                                    && !type.getCanonicalText().startsWith("byte[]") && !type.getCanonicalText().startsWith("short[]")
                                    && !type.equalsToText("Int3") && !(supportedType.contains(type.toString().replace("PsiType:","")))){
                                holder.registerProblem(
                                        variable,
                                        "Unsupported datatype in TornadoVM.",
                                        ProblemHighlightType.ERROR
                                );
                            }
                        }
                    });
                }
            }
        };
    }
}

class Conf{
    String[] datatype;
}


