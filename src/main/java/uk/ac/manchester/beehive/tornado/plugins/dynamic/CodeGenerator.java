/*
 * Copyright (c) 2023, 2025-2026, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.ac.manchester.beehive.tornado.plugins.dynamic;

import com.intellij.psi.PsiMethod;
import com.intellij.util.concurrency.annotations.RequiresReadLock;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessGenerator;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModelBuilder;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.UnsupportedParameterTypeException;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoTWTask;
import uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow.TornadoTaskService.TaskEntry;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates the Java test harnesses that dynamic inspection compiles and runs on
 * TornadoVM, one per selected task: {@link #collect} reads the PSI into a
 * {@link HarnessModel} per task, {@link #render} turns a model into source.
 */
public final class CodeGenerator {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom random = new SecureRandom();

    private CodeGenerator() {
    }

    private static String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    /**
     * A selected task and the model of its harness.
     *
     * @param displayName the task as listed in the tool window, e.g. {@code vadd(FloatArray, FloatArray): void}
     * @param model       the harness model
     */
    public record TaskModel(@NotNull String displayName, @NotNull HarnessModel model) {
    }

    /**
     * A generated test harness.
     *
     * @param displayName the task as listed in the tool window
     * @param className   the name of the generated public class (and of its source file)
     * @param source      the Java source of the harness
     */
    public record Harness(@NotNull String displayName, @NotNull String className, @NotNull String source) {
    }

    /**
     * The models of a selection of tasks.
     *
     * @param tasks      one per task that still exists, in selection order
     * @param staleTasks the display names of selected tasks that no longer exist
     */
    public record Batch(@NotNull List<TaskModel> tasks, @NotNull List<String> staleTasks) {
        public Batch {
            tasks = List.copyOf(tasks);
            staleTasks = List.copyOf(staleTasks);
        }
    }

    /**
     * Resolves the selected tasks and builds the harness model of each of them.
     * Everything the rest of the pipeline needs is copied out of the PSI here.
     */
    @RequiresReadLock
    public static @NotNull Batch collect(@NotNull List<TaskEntry> data) {
        List<TaskModel> models = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        Set<String> classNames = new HashSet<>();
        for (TaskEntry entry : data) {
            PsiMethod method = entry.resolve();
            if (method == null || !method.isValid() || method.getContainingFile() == null) {
                stale.add("'" + entry.displayName() + "'");
                continue;
            }
            String className;
            do {
                className = method.getName() + randomAlphanumeric(5);
            } while (!classNames.add(className));
            models.add(new TaskModel(TornadoTWTask.psiMethodFormat(method), HarnessModelBuilder.build(method, className)));
        }
        return new Batch(models, stale);
    }

    /**
     * Generates the source of a task's harness.
     *
     * @param parameterSize the size of the generated test data
     * @throws UnsupportedParameterTypeException if no test data can be generated for a parameter
     */
    public static @NotNull Harness render(@NotNull TaskModel task, int parameterSize)
            throws UnsupportedParameterTypeException {
        HarnessModel model = task.model();
        return new Harness(task.displayName(), model.className(),
                HarnessGenerator.generate(model, parameterSize, new Random()));
    }
}
