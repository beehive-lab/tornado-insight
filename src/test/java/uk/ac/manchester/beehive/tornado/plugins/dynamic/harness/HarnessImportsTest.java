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

package uk.ac.manchester.beehive.tornado.plugins.dynamic.harness;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HarnessImportsTest {

    @Test
    public void statementsAreNormalisedSortedAndUnique() {
        HarnessImports imports = new HarnessImports(List.of("import b.B;", "import  a.A ;", "import a . A;",
                "import static c.C.max;", "import d.*;"));
        imports.require("b.B");

        assertEquals("""
                import a.A;
                import b.B;
                import d.*;
                import static c.C.max;
                """, imports.source());
    }

    @Test
    public void typesAreWrittenBySimpleNameWhenThatResolves() {
        HarnessImports imports = new HarnessImports(List.of());

        assertEquals("int", imports.use("int"));
        assertEquals("String[]", imports.use("java.lang.String[]"));
        assertEquals("FloatArray", imports.use("FloatArray"));
        assertEquals("IntArray[][]", imports.use("a.b.IntArray[][]"));
        assertEquals("java.util.List<java.lang.Integer>", imports.use("java.util.List<java.lang.Integer>"));
        assertEquals("Outer.Inner", imports.use("Outer.Inner"));
        assertEquals("import a.b.IntArray;\n", imports.source());
    }

    @Test
    public void aTakenSimpleNameIsNotImportedAgain() {
        HarnessImports imports = new HarnessImports(List.of("import x.IntArray;"));

        assertTrue(imports.require("x.IntArray"));
        assertFalse(imports.require("y.IntArray"));
        assertEquals("y.IntArray", imports.use("y.IntArray"));
        assertEquals("import x.IntArray;\n", imports.source());
    }
}
