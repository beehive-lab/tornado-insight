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

package uk.ac.manchester.beehive.tornado.plugins.ui.toolwindow;

import com.intellij.openapi.ui.DialogPanel;
import com.intellij.ui.dsl.builder.BuilderKt;
import com.intellij.ui.dsl.builder.HyperlinkEventAction;
import com.intellij.ui.dsl.builder.Panel;
import com.intellij.ui.dsl.builder.UtilsKt;
import kotlin.Unit;

import javax.swing.*;

/**
 * The "Inspector" tab of the TornadoVM tool window: one collapsible group per
 * inspection, with a short summary and an explanation of the TornadoVM limitation.
 */
final class InspectorInfo {

    private InspectorInfo() {
    }

    static DialogPanel inspectorPane() {
        return BuilderKt.panel(panel -> {
            group(panel, "Data Type Inspector",
                    "This inspector is used to check if there are unsuppoted date type",
                    "TornadoVM currently supports Java primitive types, primitive arrays as well as some object "
                            + "types such as VectorFloat, VectorFloat4 and all variations with types as well as matrices types. "
                            + "TornadoVM generates specialized OpenCL, PTX and SPIR-V code for those data structures. "
                            + "For example, VectorFloat4 is compiled in OpenCL to utilize the OpenCL vector data "
                            + "types (e.g., float4, int4, etc.). This might speed up user code if the target device contains "
                            + "explicit vector units,such as AVX on Intel CPUs or vector registers on AMD GPUs.");

            group(panel, "Assert Inspector",
                    "The inspector checks if the Assert statement is included in a Tornado Task",
                    "TornadoVM does not support the assert statement.");

            group(panel, "Recursion Inspector",
                    "The inspector reports the presence of direct or \n"
                            + "indirect recursive calls in the Tornado Task",
                    "TornadoVM does not support recursion. This is also a current limitation of OpenCL, CUDA and SPIR-V.");

            group(panel, "Static Task and TaskGraph Inspector",
                    "The inspector reports the presence of static Tasks \n"
                            + "and static TaskGraph in the current file",
                    "TornadoVM currently does not support static TaskGraph and Tasks."
                            + " The reason for not supporting this is that a deadlock might occur between the user thread running"
                            + " class initialization and the Tornado compiler thread performing JIT compilation of the Task method.");

            group(panel, "Traps/Exceptions Inspector",
                    "The inspector reports the existence of exception throws or \n"
                            + "handler statements in the current Tornado Task, such as \n"
                            + "Try/Catch code blocks and exception throws.",
                    "TornadoVM does not support Traps/Exceptions. On GPUs there is little support for "
                            + "exceptions. For example, on a division by 0 scenario, the CPU sets a flag in one of the special "
                            + "registers. Then the Operating System can query those special registers and pass that value to the "
                            + "application runtime (in this case, the Java runtime). Then the Java runtime handles the exception. "
                            + "However, there is no such mechanism on GPUs which means that TornadoVM must insert extra "
                            + "control-flow to guarantee that those exceptions never happen. Currently, since TornadoVM compiles "
                            + "at runtime, many of those checks can be assured at runtime.");

            panel.row((JLabel) null, row -> {
                row.comment("Find more on the <a href=\"https://tornadovm.readthedocs.io/en/latest\">TornadoVM documentation</a>",
                        UtilsKt.MAX_LINE_LENGTH_WORD_WRAP, HyperlinkEventAction.HTML_HYPERLINK_INSTANCE);
                return Unit.INSTANCE;
            });
            return Unit.INSTANCE;
        });
    }

    /**
     * Adds a collapsible group with one labelled row and its comment.
     */
    private static void group(Panel panel, String title, String label, String comment) {
        panel.collapsibleGroup(title, true, group -> {
            group.row(label, row -> Unit.INSTANCE)
                    .rowComment(comment, UtilsKt.DEFAULT_COMMENT_WIDTH, HyperlinkEventAction.HTML_HYPERLINK_INSTANCE);
            return Unit.INSTANCE;
        });
    }
}
