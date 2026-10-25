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

package uk.ac.manchester.beehive.tornado.plugins.dynamic;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;

/**
 * Formats a generated test program with the project's Java code style, so the copy the debug dump
 * keeps reads like hand-written code. The harness generator indents only the code it copies from
 * the kernel's class. Used only while the dump is on: otherwise nobody sees the test.
 */
final class HarnessFormatter {

    private static final Logger LOG = Logger.getInstance(HarnessFormatter.class);

    private HarnessFormatter() {
    }

    /**
     * Returns {@code source} reformatted, or unchanged if the formatter fails: formatting only
     * changes whitespace, so the unformatted program is just as correct.
     */
    static @NotNull String format(@NotNull Project project, @NotNull String className, @NotNull String source) {
        try {
            // A non-physical file without PSI events: reformatting it then needs only a read
            // action, so it runs on the pipeline's background thread, not the UI thread
            return ReadAction.compute(() -> {
                PsiFile file = PsiFileFactory.getInstance(project).createFileFromText(className + ".java", JavaLanguage.INSTANCE, source,
                        false, false);
                return CodeStyleManager.getInstance(project).reformat(file).getText();
            });
        } catch (RuntimeException e) {
            if (e instanceof ControlFlowException) {
                throw e;
            }
            LOG.warn("Could not format the generated test " + className + "; using it unformatted", e);
            return source;
        }
    }
}
