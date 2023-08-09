package com.tais.tornado_plugins.inspector;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.tais.tornado_plugins.entity.ProblemMethods;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
                                ProblemMethods.getInstance().addMethod(parent);
                                holder.registerProblem(
                                        variable,
                                        "TornadoVM:Unsupported datatype in TornadoVM.",
                                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                                );
                            }
                        }

                    });
                }
            }
//            @Override
//            public void visitFile(@NotNull PsiFile file) {
//                super.visitFile(file);
//                ProblemMethods.getInstance().addMethod(DataTypeInspection.this, methods);
//            }
        };
    }
}

class Conf{
    String[] datatype;
}


