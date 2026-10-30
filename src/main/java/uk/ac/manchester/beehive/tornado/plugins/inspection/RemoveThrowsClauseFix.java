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

package uk.ac.manchester.beehive.tornado.plugins.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiReferenceList;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

/**
 * Removes a kernel's {@code throws} clause. A caller that catches one of the removed checked
 * exceptions, and nothing else throws it there, no longer compiles; the inspection
 * description says so.
 */
final class RemoveThrowsClauseFix implements LocalQuickFix {

    @Override
    public @NotNull String getFamilyName() {
        return MessageBundle.message("quickfix.removeThrows");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        if (descriptor.getPsiElement() instanceof PsiReferenceList throwsList) {
            for (PsiJavaCodeReferenceElement exception : throwsList.getReferenceElements()) {
                exception.delete();
            }
        }
    }
}
