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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

/**
 * <em>Convert to TornadoVM Kernel (AI)…</em> in the editor's context menu, for the method at
 * the caret.
 */
public class ConvertToKernelAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiMethod method = methodAtCaret(e);
        Presentation presentation = e.getPresentation();
        if (!ConversionLauncher.isConvertible(method)) {
            presentation.setEnabledAndVisible(false);
            return;
        }
        // Shown, but disabled, where the TornadoVM API is missing, saying how to add it: a
        // hidden item gives no hint why it is not there
        presentation.setVisible(true);
        if (ConversionLauncher.hasTornadoApi(method)) {
            presentation.setEnabled(true);
            presentation.setText(MessageBundle.message("action.tornado.convertToKernel.text"));
            presentation.setDescription(MessageBundle.message("action.tornado.convertToKernel.description"));
        } else {
            presentation.setEnabled(false);
            presentation.setText(MessageBundle.message("action.tornado.convertToKernel.noApi.text"));
            presentation.setDescription(ConversionLauncher.missingApiMessage(method));
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        PsiMethod method = methodAtCaret(e);
        if (e.getProject() != null && ConversionLauncher.isConvertible(method)) {
            ConversionLauncher.start(e.getProject(), method);
        }
    }

    private static @Nullable PsiMethod methodAtCaret(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || !(file instanceof PsiJavaFile)) {
            return null;
        }
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }
}
