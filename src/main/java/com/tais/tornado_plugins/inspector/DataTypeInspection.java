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
import com.tais.tornado_plugins.util.MessageBundle;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * This inspection tool identifies and highlights unsupported data types
 * within methods that are annotated with either "@Parallel" or "@Reduce".
 *
 * <p>Methods with either of these annotations should use data types that are
 * supported by the TornadoVM. Any usage of unsupported data types will be
 * highlighted as an error in the IDE.</p>
 */
public class DataTypeInspection extends AbstractBaseJavaLocalInspectionTool {
    // List to hold the types supported as fetched from the config file.
    static List<String> supportedType;
    // Load supported data types from the configuration file during class initialization.
    static {
        InputStream resource = DataTypeInspection.class.getClassLoader().getResourceAsStream("conf.json");
        assert resource != null;
        JsonReader reader = new JsonReader(new InputStreamReader(resource));
        Conf types = new Gson().fromJson(reader, Conf.class);
        supportedType = Arrays.asList(types.datatype);
    }

    /**
     * Constructs and returns a PsiElementVisitor that checks Java annotations for
     * specific criteria and highlights problematic data types.
     *
     * @param holder     Holds the problems identified during inspection.
     * @param isOnTheFly Indicates whether the inspection is being run on-the-fly
     *                   while the user is editing.
     * @return A visitor to traverse and inspect Java elements.
     */
    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAnnotation(PsiAnnotation annotation) {
                // Check if the annotation is of type 'Parallel' or 'Reduce'
                if (Objects.requireNonNull(annotation.getQualifiedName()).endsWith("Parallel") ||
                        annotation.getQualifiedName().endsWith("Reduce")) {
                    PsiMethod parent = PsiTreeUtil.getParentOfType(annotation, PsiMethod.class);
                    assert parent != null;
                    // Visit all elements inside the method to check for unsupported data types
                    parent.accept(new JavaRecursiveElementVisitor() {
                        @Override
                        public void visitLocalVariable(PsiLocalVariable variable) {
                            checkVariable(variable.getType(), variable);
                        }

                        @Override
                        public void visitField(PsiField field) {
                            checkVariable(field.getType(), field);
                        }

                        @Override
                        public void visitParameter(PsiParameter parameter) {
                            checkVariable(parameter.getType(), parameter);
                        }

                        /**
                         * Checks the type of the provided variable. If the type is unsupported,
                         * the variable is registered as a problem.
                         *
                         * @param type     The type of the variable to be checked.
                         * @param variable The variable itself.
                         */
                        private void checkVariable(PsiType type, PsiVariable variable) {
                            if (!type.equalsToText("int") && !type.equalsToText("boolean") && !type.equalsToText("double")
                                    && !type.equalsToText("long") && !type.equalsToText("char") && !type.equalsToText("float")
                                    && !type.equalsToText("byte") && !type.equalsToText("short")
                                    && !type.getCanonicalText().startsWith("int[]") && !type.getCanonicalText().startsWith("boolean[]")
                                    && !type.getCanonicalText().startsWith("double[]") && !type.getCanonicalText().startsWith("long[]")
                                    && !type.getCanonicalText().startsWith("char[]") && !type.getCanonicalText().startsWith("float[]")
                                    && !type.getCanonicalText().startsWith("byte[]") && !type.getCanonicalText().startsWith("short[]")
                                    && !type.equalsToText("Int3") && !(supportedType.contains(type.toString().replace("PsiType:", "")))) {
                                ProblemMethods.getInstance().addMethod(parent);
                                holder.registerProblem(
                                        variable,
                                        MessageBundle.message("inspection.datatype"),
                                        ProblemHighlightType.ERROR
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

/**
 * Represents the structure of the conf.json file,
 * which contains information about supported data types.
 */
class Conf {
    String[] datatype;
}


