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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompilerOutputSummarizerTest {

    @Test
    public void taskTypeMismatchIsCondensedAndExplained() {
        // Real javac 25 output for a harness passing an IntArray to a DoubleArray kernel parameter
        String stderr = CapturedOutputs.load("javac-task-type-mismatch.txt");
        String summary = CompilerOutputSummarizer.summarize(stderr);

        assertTrue(summary, summary.startsWith(
                "src/MismatchXy7Qz.java:22: error: no suitable method found for task(String,MismatchXy7Qz::vadd,IntArray,DoubleArray,DoubleArray)"));
        assertTrue(summary, summary.contains(".task(\"insightTaskName\", MismatchXy7Qz::vadd, a, b, c)"));
        assertTrue(summary, summary.contains("upper bounds: DoubleArray,Object"));
        assertTrue(summary, summary.contains("lower bounds: IntArray"));
        assertTrue(summary, summary.contains("kernel 'vadd's parameters"));
        assertTrue(summary, summary.contains("The kernel parameter expects: DoubleArray"));
        assertTrue(summary, summary.contains("The generated harness supplied: IntArray"));
        // The overload candidates and type-variable declarations are dropped
        assertFalse(summary, summary.contains("extends Object declared in method"));
        assertFalse(summary, summary.contains("is not applicable"));
        assertTrue(summary.lines().count() < 25);
    }

    @Test
    public void otherErrorsKeepHeaderAndSourceLineWithoutTypeExplanation() {
        String stderr = """
                /tmp/files123/vectorAddAb12C.java:31: error: cannot find symbol
                        c.set(i, a.get(i) + SCALE);
                                            ^
                  symbol:   variable SCALE
                  location: class vectorAddAb12C
                1 error
                """;
        String summary = CompilerOutputSummarizer.summarize(stderr);
        assertTrue(summary, summary.startsWith("/tmp/files123/vectorAddAb12C.java:31: error: cannot find symbol"));
        assertTrue(summary, summary.contains("c.set(i, a.get(i) + SCALE);"));
        assertFalse(summary, summary.contains("What this means"));
    }

    @Test
    public void outputWithoutDiagnosticsIsReturnedAsIs() {
        String stderr = "error: invalid source release: 26\nUsage: javac <options> <source files>\n";
        assertEquals(stderr.strip(), CompilerOutputSummarizer.summarize(stderr));
    }

    @Test
    public void emptyOutputGetsAnExplanation() {
        assertTrue(CompilerOutputSummarizer.summarize("  ").contains("produced no output"));
        assertTrue(CompilerOutputSummarizer.summarize(null).contains("produced no output"));
    }
}
