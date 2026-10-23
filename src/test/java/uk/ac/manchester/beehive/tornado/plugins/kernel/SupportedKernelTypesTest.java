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

package uk.ac.manchester.beehive.tornado.plugins.kernel;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SupportedKernelTypesTest {

    @Test
    public void resourceListsTheSupportedTypes() {
        // The lists DataTypeInspection held in code before they moved to the resource
        SupportedKernelTypes types = SupportedKernelTypes.get();
        assertEquals(List.of("int", "boolean", "double", "long", "char", "float", "byte", "short", "Int3"),
                types.names());
        assertEquals(List.of("int[]", "boolean[]", "double[]", "long[]", "char[]", "float[]", "byte[]", "short[]",
                "uk.ac.manchester.tornado.api.", "java.util.concurrent.atomic.AtomicInteger"), types.prefixes());
    }

    @Test
    public void loadedOnce() {
        assertTrue(SupportedKernelTypes.get() == SupportedKernelTypes.get());
    }

    @Test
    public void commentsAndBlankLinesAreIgnored() {
        SupportedKernelTypes types = SupportedKernelTypes.parse(List.of(
                "# header", "", "[names]", "  int  ", "# int[] is a prefix", "[prefixes]", "int[]"), "test");
        assertEquals(List.of("int"), types.names());
        assertEquals(List.of("int[]"), types.prefixes());
    }

    @Test
    public void malformedContentIsRejected() {
        assertMalformed("malformed test line 1: entry outside a section", "int", "[names]", "int", "[prefixes]", "int[]");
        assertMalformed("malformed test line 3: unknown section [types]", "[names]", "int", "[types]", "x");
        assertMalformed("malformed test line 2: entry contains whitespace", "[names]", "int long", "[prefixes]", "int[]");
        assertMalformed("malformed test: [names] and [prefixes] must both list types", "[names]", "int");
        assertMalformed("malformed test: [names] and [prefixes] must both list types");
    }

    private static void assertMalformed(String message, String... lines) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SupportedKernelTypes.parse(List.of(lines), "test"));
        assertEquals(message, e.getMessage());
    }
}
