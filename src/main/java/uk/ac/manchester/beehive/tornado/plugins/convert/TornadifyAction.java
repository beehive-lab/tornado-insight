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

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider;
import com.intellij.openapi.actionSystem.ex.TooltipLinkProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingsConfigurable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

/**
 * <em>Tornadify</em> in the toolbar of the TornadoVM tool window: transforms the method at the
 * caret of the selected editor. The tool window has the focus when it is clicked, so the editor
 * usually comes from {@link FileEditorManager}, not from the data context.
 * <p>
 * Its tooltip is a short guide: it names the method it will transform, or, while it is disabled,
 * says how to use it (click inside a method, then the button) and the other ways to start. A
 * toolbar button shows the description under the title only for a {@link TooltipDescriptionProvider}.
 * While the transformation is off, the tooltip has an <em>Open settings</em> link.
 * </p>
 */
public class TornadifyAction extends AnAction implements TooltipDescriptionProvider, TooltipLinkProvider {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        presentation.setText(MessageBundle.message("action.tornado.tornadify.text"), false);
        if (!AiSettingState.getInstance().enabled) {
            presentation.setEnabled(false);
            presentation.setDescription(MessageBundle.message("convert.toolbar.off"));
            return;
        }
        Editor editor = editor(e);
        if (editor != null && e.getProject() != null
                && PsiDocumentManager.getInstance(e.getProject()).isUncommited(editor.getDocument())) {
            // Just typed: the PSI does not match the caret yet, so it is not read and no method
            // is named until the document is committed; actionPerformed commits it first
            presentation.setEnabled(true);
            presentation.setDescription(MessageBundle.message("action.tornado.tornadify.description"));
            return;
        }
        PsiMethod method = methodAtCaret(e);
        String problem = problem(javaFile(e), method);
        presentation.setEnabled(problem == null);
        if (problem == null) {
            // Names the method, so it is clear the button acts on it alone
            presentation.setText(MessageBundle.message("convert.toolbar.method.text", method.getName()), false);
            presentation.setDescription(MessageBundle.message("convert.toolbar.method.description", method.getName()));
        } else {
            presentation.setDescription(problem);
        }
    }

    // The button rebuilds its tooltip when the description changes, so this follows the setting
    @Override
    public @Nullable TooltipLink getTooltipLink(@Nullable JComponent owner) {
        if (AiSettingState.getInstance().enabled) {
            return null;
        }
        return new TooltipLink(MessageBundle.message("convert.toolbar.off.link"), () -> {
            Project project = owner == null ? null
                    : CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(owner));
            ShowSettingsUtil.getInstance().showSettingsDialog(project, AiSettingsConfigurable.class);
        });
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = editor(e);
        if (editor != null && e.getProject() != null) {
            // The caret offset is in the document, so the PSI must match it
            PsiDocumentManager.getInstance(e.getProject()).commitDocument(editor.getDocument());
        }
        PsiMethod method = methodAtCaret(e);
        if (e.getProject() != null && ConversionLauncher.isConvertible(method)) {
            ConversionLauncher.start(e.getProject(), method);
        }
    }

    /**
     * What the tooltip says while the button cannot transform {@code method}, the method at the
     * caret of {@code file}, or {@code null} if it can, with the transformation on: the file's
     * module lacks the TornadoVM API, wherever the caret is; else how to use the button.
     */
    private static @Nullable String problem(@Nullable PsiJavaFile file, @Nullable PsiMethod method) {
        if (file != null && JavaPsiFacade.getInstance(file.getProject())
                .findClass(TornadoKernels.PARALLEL, file.getResolveScope()) == null) {
            // The same advice as the editor menu, for the JDK of the method's module
            return MessageBundle.message("convert.toolbar.noApi",
                    ConversionLauncher.missingApiMessage(method != null ? method : file));
        }
        if (!ConversionLauncher.isConvertible(method)) {
            return MessageBundle.message("action.tornado.tornadify.description");
        }
        return null;
    }

    private static @Nullable PsiMethod methodAtCaret(AnActionEvent e) {
        Editor editor = editor(e);
        PsiJavaFile file = javaFile(e);
        if (editor == null || file == null) {
            return null;
        }
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }

    // The Java file in the editor, or null if the editor has another kind of file
    private static @Nullable PsiJavaFile javaFile(AnActionEvent e) {
        Editor editor = editor(e);
        if (editor == null || e.getProject() == null) {
            return null;
        }
        PsiFile file = PsiDocumentManager.getInstance(e.getProject()).getPsiFile(editor.getDocument());
        return file instanceof PsiJavaFile javaFile ? javaFile : null;
    }

    // The editor in the data context, else the selected one. getSelectedTextEditor() needs the
    // EDT, while update() runs in the background, so the selected TextEditor gives it instead
    private static @Nullable Editor editor(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor != null || project == null) {
            return editor;
        }
        FileEditor selected = FileEditorManager.getInstance(project).getSelectedEditor();
        return selected instanceof TextEditor textEditor ? textEditor.getEditor() : null;
    }
}
