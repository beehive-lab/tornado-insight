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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Project service holding the TornadoVM tasks shown in the tool window for the
 * file currently selected in the editor.
 * <p>
 * The state is an immutable {@link TaskSnapshot} that is computed in a
 * non-blocking read action and published on the UI thread together with the
 * list model, so the panel and the snapshot always agree, and a discarded or
 * expired computation never overwrites them. PSI is only referenced through
 * smart pointers, so a snapshot does not keep a closed file or project alive.
 * </p>
 */
@Service(Service.Level.PROJECT)
public final class TornadoTaskService implements Disposable {

    /**
     * A task (kernel method) listed in the tool window.
     *
     * @param displayName the text rendered in the list
     * @param method      pointer to the kernel method
     */
    public record TaskEntry(@NotNull String displayName, @NotNull SmartPsiElementPointer<PsiMethod> method) {

        /**
         * Returns the kernel method, or {@code null} if it no longer exists.
         * Must be called with read access.
         */
        @RequiresReadLock
        public @Nullable PsiMethod resolve() {
            return method.getElement();
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * The tasks found in one file, and the data that dynamic inspection reuses
     * from that file.
     *
     * @param file       pointer to the file the tasks were found in
     * @param tasks      the valid tasks, in the order they are listed
     * @param importCode the file's import statements (JUnit imports removed)
     */
    public record TaskSnapshot(@NotNull SmartPsiElementPointer<PsiFile> file, @NotNull List<TaskEntry> tasks,
                               @NotNull String importCode) {
        public TaskSnapshot {
            tasks = List.copyOf(tasks);
        }
    }

    private final Project project;
    private volatile TaskSnapshot snapshot;

    public TornadoTaskService(Project project) {
        this.project = project;
    }

    public static @NotNull TornadoTaskService getInstance(@NotNull Project project) {
        return project.getService(TornadoTaskService.class);
    }

    /**
     * Returns the last published snapshot, or {@code null} if none was published yet.
     */
    public @Nullable TaskSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * Recomputes the tasks of {@code virtualFile} in the background and, on the UI
     * thread, publishes the new snapshot and replaces the content of {@code model}.
     * A newer refresh cancels a pending one.
     *
     * @param virtualFile the file to scan
     * @param model       the tool window's list model
     */
    public void refresh(@NotNull VirtualFile virtualFile, @Nullable DefaultListModel<TaskEntry> model) {
        // No isDumb() early return: inSmartMode() below defers the computation until
        // indexing finishes, so a refresh requested during indexing is not lost
        if (model == null) return;

        ReadAction.nonBlocking(() -> {
                    if (!virtualFile.isValid()) return null;
                    PsiFile file = PsiManager.getInstance(project).findFile(virtualFile);
                    if (file == null) return null;
                    return computeSnapshot(file);
                })
                .inSmartMode(project)
                .expireWith(this)
                .coalesceBy(this)
                .finishOnUiThread(ModalityState.defaultModalityState(), newSnapshot -> {
                    if (newSnapshot == null) return;
                    snapshot = newSnapshot;
                    // Keep the model, and with it the user's selection, when the tasks are unchanged
                    if (ApplicationManager.getApplication().runReadAction(
                            (Computable<Boolean>) () -> sameTasks(model, newSnapshot.tasks()))) return;
                    model.clear();
                    for (TaskEntry entry : newSnapshot.tasks()) {
                        model.addElement(entry);
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private boolean sameTasks(DefaultListModel<TaskEntry> model, List<TaskEntry> tasks) {
        if (model.size() != tasks.size()) return false;
        SmartPointerManager pointers = SmartPointerManager.getInstance(project);
        for (int i = 0; i < tasks.size(); i++) {
            TaskEntry shown = model.get(i);
            TaskEntry task = tasks.get(i);
            if (!shown.displayName().equals(task.displayName())
                    || !pointers.pointToTheSameElement(shown.method(), task.method())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Computes the snapshot of {@code file}: the kernels that are valid TornadoVM
     * tasks, with their display names, and the file's import code.
     */
    @RequiresReadLock
    public static @NotNull TaskSnapshot computeSnapshot(@NotNull PsiFile file) {
        SmartPointerManager pointers = SmartPointerManager.getInstance(file.getProject());
        List<TaskEntry> entries = new ArrayList<>();
        for (PsiMethod task : TornadoTWTask.findSuitableMethods(file)) {
            if (TornadoTWTask.validateTask(task)) {
                entries.add(new TaskEntry(TornadoTWTask.psiMethodFormat(task), pointers.createSmartPsiElementPointer(task)));
            }
        }
        return new TaskSnapshot(pointers.createSmartPsiElementPointer(file), entries, TornadoTWTask.getImportCode(file));
    }

    @Override
    public void dispose() {
        snapshot = null;
    }
}
