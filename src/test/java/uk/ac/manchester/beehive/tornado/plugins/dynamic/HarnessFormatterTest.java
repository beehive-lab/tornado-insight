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

import com.intellij.openapi.application.ApplicationManager;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;

public class HarnessFormatterTest extends TornadoTestCase {

    /** The shape the harness generator emits: copied code indented, its own lines flush left. */
    private static final String GENERATED = """
            import uk.ac.manchester.tornado.api.TaskGraph;
            public class vectorAddX7k2q {
            public static void vectorAdd(int[] a, int[] b) {
                    for (int i = 0; i < a.length; i++) { b[i] = a[i]; }
                }
            public static void main(String[] args) {
            int[] a = new int[128];
            int[] b = new int[128];
            TaskGraph taskGraph = new TaskGraph("s0") \s
            .task("t0", vectorAddX7k2q::vectorAdd, a, b) \s
            .transferToHost(b);
            }
            }
            """;

    public void testIndentsTheGeneratedCode() throws Exception {
        String formatted = formatOffTheUiThread();

        assertTrue(formatted, formatted.contains("\n    public static void main(String[] args) {\n"));
        assertTrue(formatted, formatted.contains("\n        int[] a = new int[128];\n"));
        // Continuation lines of the TaskGraph chain are indented under it
        assertTrue(formatted, formatted.contains("\n                .task(\"t0\", vectorAddX7k2q::vectorAdd, a, b)"));
        assertFalse("no trailing spaces", formatted.contains(" \n"));
    }

    public void testChangesOnlyWhitespace() throws Exception {
        String formatted = formatOffTheUiThread();
        assertEquals(GENERATED.replaceAll("\\s+", ""), formatted.replaceAll("\\s+", ""));
    }

    /** The pipeline formats on its background thread, so the test does too. */
    private String formatOffTheUiThread() throws Exception {
        return ApplicationManager.getApplication()
                .executeOnPooledThread(() -> HarnessFormatter.format(getProject(), "vectorAddX7k2q", GENERATED))
                .get();
    }
}
