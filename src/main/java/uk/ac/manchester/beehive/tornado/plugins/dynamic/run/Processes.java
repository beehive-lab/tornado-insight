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

package uk.ac.manchester.beehive.tornado.plugins.dynamic.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the child processes of dynamic inspection.
 */
public final class Processes {

    private Processes() {
    }

    /**
     * Runs {@code commandLine} to completion, or until {@code timeoutMs} passes or
     * the user cancels. A cancelled run throws {@link ProcessCanceledException}; a
     * timed-out one is destroyed and returns an output whose
     * {@link ProcessOutput#isTimeout()} is set.
     *
     * @throws ExecutionException if the process cannot be started
     */
    public static @NotNull ProcessOutput run(@NotNull GeneralCommandLine commandLine, @NotNull ProgressIndicator indicator,
                                             int timeoutMs) throws ExecutionException {
        ProcessOutput output = new CapturingProcessHandler(commandLine)
                .runProcessWithProgressIndicator(indicator, timeoutMs);
        if (output.isCancelled()) {
            throw new ProcessCanceledException();
        }
        return output;
    }

    /**
     * Formats a timeout for messages, e.g. "2 minutes" or "60 seconds".
     */
    public static @NotNull String describeTimeout(int timeoutMs) {
        int seconds = timeoutMs / 1000;
        return seconds % 60 == 0 && seconds >= 120 ? seconds / 60 + " minutes" : seconds + " seconds";
    }
}
