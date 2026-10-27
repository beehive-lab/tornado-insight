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

package uk.ac.manchester.beehive.tornado.plugins.kernel;

import com.intellij.psi.*;

import java.util.*;

/**
 * Helpers to find the TornadoVM kernels of a file and format them for display.
 * <p>
 * It holds no state: every helper takes the file or methods it works on, and the
 * tasks of the current file are kept per project by
 * {@link uk.ac.manchester.beehive.tornado.plugins.sync.TornadoTaskService}.
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
    }}
