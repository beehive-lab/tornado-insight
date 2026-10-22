/*
 * Copyright (c) 2026, APT Group, Department of Computer Science,
 *  The University of Manchester.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness;

import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.LocalMemoryParameterAnalyzer;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Field;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.TaskGraphDeclaration;
import uk.ac.manchester.beehive.tornado.plugins.inspector.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.util.TornadoTWTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@link HarnessModel} of a kernel from the PSI. Must run in a read
 * action; the model it returns holds no PSI.
 */
public final class HarnessModelBuilder {

    private HarnessModelBuilder() {
    }

    /**
     * Builds the model of the harness that runs {@code kernel} as class {@code className}.
     */
    @RequiresReadLock
    public static @NotNull HarnessModel build(@NotNull PsiMethod kernel, @NotNull String className) {
        PsiFile psiFile = kernel.getContainingFile();
        List<Field> fields = collectFields(psiFile);

        List<String> helpers = new ArrayList<>();
        ArrayList<PsiMethod> kernels = new ArrayList<>(List.of(kernel));
        for (PsiMethod helper : TornadoTWTask.getCalledMethods(kernels)) {
            helpers.add(helper.getText());
        }

        String methodWithClass = className + "::" + kernel.getName();
        // The class may declare several TaskGraphs that reference this kernel by
        // name; prefer the one whose .task(...) argument count matches the
        // kernel's parameter count so we don't pick a stale/unrelated TaskGraph.
        int kernelParamCount = kernel.getParameterList().getParametersCount();
        Optional<String> originalTaskGraph = TornadoTWTask.extractOriginalTaskGraphDeclaration(psiFile, kernel.getName(),
                methodWithClass, kernelParamCount);
        TaskGraphDeclaration taskGraph = originalTaskGraph
                .filter(declaration -> declaration.contains(".task("))
                .map(declaration -> new TaskGraphDeclaration(declaration,
                        TornadoTWTask.extractTaskGraphVariableName(declaration).orElse("taskGraph"), null, null))
                .orElse(null);

        List<TornadoTWTask.TaskParametersInfo> taskArguments = TornadoTWTask
                .extractTasksParameters(psiFile, kernel.getName(), kernelParamCount)
                .orElse(List.of());

        return new HarnessModel(className, kernel.getName(), TornadoKernels.kind(kernel),
                TornadoTWTask.getImportCode(psiFile).lines().toList(), fields, kernel.getText(), helpers,
                parameters(kernel, taskArguments, fields), taskGraph);
    }

    private static List<Field> collectFields(PsiFile psiFile) {
        List<Field> fields = new ArrayList<>();
        for (PsiField field : PsiTreeUtil.findChildrenOfType(psiFile, PsiField.class)) {
            List<String> modifiers = new ArrayList<>();
            for (String modifier : PsiModifier.MODIFIERS) {
                if (field.hasModifierProperty(modifier)) {
                    modifiers.add(modifier);
                }
            }
            PsiExpression initializer = field.getInitializer();
            fields.add(new Field(String.join(" ", modifiers), field.getType().getPresentableText(), field.getName(),
                    initializer != null ? initializer.getText() : null));
        }
        return fields;
    }

    private static List<Parameter> parameters(PsiMethod kernel, List<TornadoTWTask.TaskParametersInfo> taskArguments,
                                              List<Field> fields) {
        Set<String> localMemoryParams = LocalMemoryParameterAnalyzer.findLocalMemoryParameters(kernel);
        PsiParameter[] kernelParams = kernel.getParameterList().getParameters();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (taskArguments.isEmpty()) {
            // Fallback: infer from the method signature when TaskGraphs are not declared
            for (PsiParameter parameter : kernelParams) {
                names.add(parameter.getName());
                types.add(parameter.getTypeElement() != null ? parameter.getTypeElement().getText() : parameter.getType().getPresentableText());
            }
        } else {
            // The arguments passed to .task("t0", Class::kernel, arg0, arg1, ...)
            // map positionally onto the kernel's parameters. TornadoVM requires the
            // argument type to match the kernel parameter type exactly (these are
            // concrete types with no subtyping), so when the counts line up we
            // allocate each harness variable with the KERNEL parameter's type
            // rather than the type resolved from the user's source variable. We
            // keep the user's argument NAMES so the reused TaskGraph (which
            // references them) still resolves.
            boolean alignWithKernel = taskArguments.size() == kernelParams.length;
            for (int i = 0; i < taskArguments.size(); i++) {
                TornadoTWTask.TaskParametersInfo argument = taskArguments.get(i);
                names.add(argument.getName());
                String kernelType = alignWithKernel && kernelParams[i].getTypeElement() != null
                        ? kernelParams[i].getTypeElement().getText()
                        : null;
                types.add(kernelType != null ? kernelType : argument.getType());
            }
        }

        List<Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String type = types.get(i);
            String intOverride = "int".equals(type) ? findMatchingIntField(fields, name) : null;
            parameters.add(new Parameter(name, type, localMemoryParams.contains(name), intOverride));
        }
        return parameters;
    }

    /**
     * For an int parameter of the kernel, look in the copied fields for an int
     * field whose name matches the parameter name (case-insensitive) - e.g.
     * parameter 'size' picks up a user-defined 'SIZE' or 'Size' constant.
     * Returns {@code null} when there is none, so the configured size is used.
     */
    static @Nullable String findMatchingIntField(List<Field> fields, String paramName) {
        for (Field field : fields) {
            if ("int".equals(field.type()) && field.name().equalsIgnoreCase(paramName)) {
                return field.name();
            }
        }
        return null;
    }
}
