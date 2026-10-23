/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.inspection.KernelValidator;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.*;

/**
 * A utility class for managing and working with ToolWindows about the TornadoVM Tasks.
 * <p>
 * This class provides functionality to detect, format, and validate TornadoVM Tasks/methods
 * within the tool window. It holds no state: every helper takes the file or methods it works
 * on, and the tasks of the current file are kept per project by {@link TornadoTaskService}.
 * </p>
 */
public class TornadoTWTask {

    /**
     * Finds the TornadoVM kernels declared in the given PsiFile, in document order.
     *
     * @param psiFile the file to search in
     * @return the kernels of the file (see {@link TornadoKernels}); empty if none found
     */
    public static List<PsiMethod> findSuitableMethods(PsiFile psiFile) {
        return TornadoKernels.findKernels(psiFile);
    }

    /**
     * Formats the PsiMethod details into a readable string format.
     *
     * @param method the PsiMethod to format
     * @return a formatted string representation of the method
     */
    public static String psiMethodFormat(PsiMethod method) {
        String methodName = method.getName();
        StringBuilder methodParameters = new StringBuilder();
        if (!method.hasParameters())
            return methodName + "(): " + Objects.requireNonNull(method.getReturnType()).getCanonicalText();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (methodParameters.isEmpty()) {
                methodParameters.append(Objects.requireNonNull(parameter.getTypeElement()).getText());
            } else {
                methodParameters.append(", ").append(Objects.requireNonNull(parameter.getTypeElement()).getText());
            }
        }
        return methodName + "(" + methodParameters + "): " + Objects.requireNonNull(method.getReturnType()).getCanonicalText();
    }

    /**
     * Returns the import statements of the given file, one per line, skipping JUnit imports.
     */
    public static String getImportCode(PsiFile file) {
        StringBuilder importCodeBlock = new StringBuilder();
        if (!(file instanceof PsiJavaFile javaFile)) {
            return importCodeBlock.toString();
        }
        PsiImportList importList = javaFile.getImportList();

        if (importList != null) {
            PsiImportStatement[] importStatements = importList.getImportStatements();
            for (PsiImportStatement importStatement : importStatements) {
                String importText = importStatement.getText();
                if (statementImportsJunit(importText)) {
                    continue;
                }
                importCodeBlock.append(importStatement.getText());
                importCodeBlock.append("\n");
            }
        }
        return importCodeBlock.toString();
    }

    // Validates a given PsiMethod to ensure it's a valid TornadoVM task
    static boolean validateTask(PsiMethod method) {
        if (method == null) return false;
        PsiElement[] errorElements = PsiTreeUtil.collectElements(method,
                element -> element instanceof PsiErrorElement);
        if (errorElements.length != 0) return false;
        // Kernel entry points registered via .task() must return void
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiTypes.voidType().equals(returnType)) return false;
        // Computed from the inspections' own checks, not from whether they have run
        return !KernelValidator.hasBlockingProblems(method);
    }

    private static boolean statementImportsJunit(String importStatement) {
        return importStatement.contains("import org.junit.");
    }
}
