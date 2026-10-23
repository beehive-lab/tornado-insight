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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw {@code javac} output for a generated test harness into a short,
 * readable explanation. Pure string processing, so it is unit tested without an IDE.
 */
public final class CompilerOutputSummarizer {

    private CompilerOutputSummarizer() {
    }

    // javac diagnostic header, e.g. "atomic18YxscA.java:49: error: no suitable method ..."
    private static final Pattern COMPILE_DIAGNOSTIC =
            Pattern.compile("\\.java:\\d+:\\s*(error|warning):");

    // Kernel method name from the offending ".task(\"t0\", Class::kernel, ...)" line.
    private static final Pattern KERNEL_REF = Pattern.compile("::(\\w+)");

    /**
     * Condenses raw {@code javac} output into the few lines that actually
     * explain the failure. The generated TornadoVM harness calls the strongly
     * typed {@code TaskGraph.task(...)} overloads, so a single argument-type
     * mismatch makes the compiler print every {@code Task1..Task15} candidate
     * plus a page of {@code where T1#1 extends Object ...} type-variable
     * declarations. We keep the diagnostic headers, the offending source line
     * and caret, and the {@code upper/lower bounds} lines that pinpoint the
     * conflicting types, dropping the candidate/type-variable noise. When the
     * failure is a {@code task(...)} type mismatch we append an explicit
     * explanation, since the usual cause is a kernel parameter whose type the
     * target device does not support (for example FP64/double on a GPU without
     * double support).
     */
    public static String summarize(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "Compilation of the generated TornadoVM test failed, but the Java compiler produced no output to explain why.";
        }
        StringBuilder summary = new StringBuilder();
        boolean typeMismatch = false;
        String kernelName = null;     // kernel whose .task(...) call failed
        String expectedType = null;   // type the kernel parameter expects (javac "upper bounds")
        String actualType = null;     // type the harness actually allocated (javac "lower bounds")
        int keepContext = 0; // remaining lines to keep after a diagnostic header (source + caret)
        for (String line : stderr.split("\n")) {
            String trimmed = line.strip();
            if (COMPILE_DIAGNOSTIC.matcher(line).find()) {
                summary.append(trimmed).append("\n");
                keepContext = 2;
                if (trimmed.contains("for task(")) {
                    typeMismatch = true;
                }
                continue;
            }
            // Bound lines pinpoint the conflicting types - always keep them.
            if (trimmed.startsWith("upper bounds:") || trimmed.startsWith("lower bounds:")
                    || trimmed.contains("has incompatible bounds")) {
                summary.append("    ").append(trimmed).append("\n");
                typeMismatch = true;
                if (trimmed.startsWith("upper bounds:")) {
                    expectedType = firstType(trimmed.substring("upper bounds:".length()));
                } else if (trimmed.startsWith("lower bounds:")) {
                    actualType = firstType(trimmed.substring("lower bounds:".length()));
                }
                continue;
            }
            if (keepContext > 0) {
                // Stop as soon as the overload-candidate / type-variable noise starts.
                if (trimmed.startsWith("method ") || trimmed.startsWith("(")
                        || trimmed.startsWith("where ")
                        || trimmed.contains("extends Object declared in method")) {
                    keepContext = 0;
                } else {
                    summary.append("    ").append(trimmed).append("\n");
                    keepContext--;
                    if (kernelName == null && trimmed.contains(".task(")) {
                        Matcher m = KERNEL_REF.matcher(trimmed);
                        if (m.find()) {
                            kernelName = m.group(1);
                        }
                    }
                }
            }
        }

        if (summary.length() == 0) {
            // No recognisable diagnostic header - fall back to the raw output so
            // we never swallow the failure entirely.
            return stderr.strip();
        }

        if (typeMismatch) {
            summary.append("\n--- What this means ---\n");
            summary.append("TornadoInsight generated a test harness that allocated the wrong data type for one of ")
                    .append(kernelName != null ? "kernel '" + kernelName + "'s parameters" : "this kernel's parameters")
                    .append(", so the strongly-typed TaskGraph.task(...) call does not type-check.\n");
            if (expectedType != null && actualType != null) {
                summary.append("    - The kernel parameter expects: ").append(expectedType).append("\n");
                summary.append("    - The generated harness supplied: ").append(actualType).append("\n");
            }
            summary.append("\nThis is a type-detection bug in TornadoInsight's harness generator, not a limitation of your device. ")
                    .append("Because this is a compile-time error, it cannot be caused by the device lacking support for a type ")
                    .append("(an unsupported type, such as FP64/double on a GPU without double-precision support, would fail at run time, not here).\n");
            summary.append("\nWhat you can do:\n");
            summary.append("    - This kernel cannot be dynamically inspected until the generator emits a matching type.\n");
            summary.append("    - Please report it, including the kernel signature and the expected/supplied types shown above.");
        }
        return summary.toString().strip();
    }

    /**
     * Picks the first concrete type from a javac bounds list such as
     * "DoubleArray,Object" or " IntArray", ignoring the trailing {@code Object}
     * upper bound that javac always adds. Returns {@code null} if nothing usable
     * is found.
     */
    private static String firstType(String boundsList) {
        for (String part : boundsList.split(",")) {
            // Keep only the leading type token; javac wraps the lower bound in
            // "(inference variable ... )", so a trailing ')' can bleed in.
            Matcher m = TYPE_TOKEN.matcher(part.strip());
            if (m.find()) {
                String t = m.group(1);
                if (!t.equals("Object")) {
                    return t;
                }
            }
        }
        return null;
    }

    // Leading Java type token, e.g. "DoubleArray", "int[]", "Foo<Bar>".
    private static final Pattern TYPE_TOKEN = Pattern.compile("^([\\w.$]+(?:\\[\\])*(?:<[^>]*>)?)");
}
