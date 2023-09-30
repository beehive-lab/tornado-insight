package com.tais.tornado_plugins.ui
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.panel

fun inspectorPane(): DialogPanel {
    return panel {
        collapsibleGroup("Data Type Inspector") {
            row("This inspector is used to check if there are unsuppoted date type") {
            }.rowComment("TornadoVM currently supports Java primitive types, primitive arrays as well as some object " +
                    "types such as VectorFloat, VectorFloat4 and all variations with types as well as matrices types. " +
                    "TornadoVM generates specialized OpenCL, PTX and SPIR-V code for those data structures. " +
                    "For example, VectorFloat4 is compiled in OpenCL to utilize the OpenCL vector data " +
                    "types (e.g., float4, int4, etc.). This might speed up user code if the target device contains " +
                    "explicit vector units,such as AVX on Intel CPUs or vector registers on AMD GPUs.")
        }

        collapsibleGroup("Assert Inspector") {
            row("The inspector checks if the Assert statement is included in a Tornado Task") {
            }.rowComment("TornadoVM does not support the assert statement.")
        }

        collapsibleGroup("Recursion Inspector") {
            row("The inspector reports the presence of direct or \n" +
                    "indirect recursive calls in the Tornado Task") {
            }.rowComment("TornadoVM does not support recursion. This is also a current limitation of OpenCL, CUDA and SPIR-V.")
        }

        collapsibleGroup("Static Task and TaskGraph Inspector") {
            row("The inspector reports the presence of static Tasks \n" +
                    "and static TaskGraph in the current file") {
            }.rowComment("TornadoVM currently does not support static TaskGraph and Tasks." +
                    " The reason for not supporting this is that a deadlock might occur between the user thread running" +
                    " class initialization and the Tornado compiler thread performing JIT compilation of the Task method.")
        }

        collapsibleGroup("Traps/Exceptions Inspector") {
            row("The inspector reports the existence of exception throws or \n" +
                    "handler statements in the current Tornado Task, such as \n" +
                    "Try/Catch code blocks and exception throws.") {
            }.rowComment("TornadoVM does not support Traps/Exceptions. On GPUs there is little support for " +
                    "exceptions. For example, on a division by 0 scenario, the CPU sets a flag in one of the special " +
                    "registers. Then the Operating System can query those special registers and pass that value to the " +
                    "application runtime (in this case, the Java runtime). Then the Java runtime handles the exception. " +
                    "However, there is no such mechanism on GPUs which means that TornadoVM must insert extra " +
                    "control-flow to guarantee that those exceptions never happen. Currently, since TornadoVM compiles " +
                    "at runtime, many of those checks can be assured at runtime.")
        }

        separator()
                .rowComment("Find more on <a href=\"https://tornadovm.readthedocs.io/en/latest\">TornadoVM documentation</a> ")
    }
}