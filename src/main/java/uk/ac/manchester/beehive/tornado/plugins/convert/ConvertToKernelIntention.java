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

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

/**
 * Alt+Enter on a method's name: <em>Convert to TornadoVM kernel (AI)</em>. Offered only when
 * AI-assisted conversion is enabled, so it does not clutter every method otherwise.
 */
public class ConvertToKernelIntention extends PsiElementBaseIntentionAction {

    @Override
    public @NotNull String getFamilyName() {
        return MessageBundle.message("convert.intention");
    }

    @Override
    public @NotNull String getText() {
        return getFamilyName();
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        return AiSettingState.getInstance().enabled && ConversionLauncher.isConvertible(method(element));
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, @NotNull PsiElement element) {
        PsiMethod method = method(element);
        if (method != null) {
            ConversionLauncher.start(project, method);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        return new IntentionPreviewInfo.Html(MessageBundle.message("convert.intention.preview"));
    }

    // The method whose name is at the caret
    private static PsiMethod method(PsiElement element) {
        return element instanceof PsiIdentifier && element.getParent() instanceof PsiMethod method
                && method.getNameIdentifier() == element ? method : null;
    }
}
