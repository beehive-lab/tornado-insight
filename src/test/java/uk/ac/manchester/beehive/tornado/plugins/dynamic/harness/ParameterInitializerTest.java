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
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.kernel.SupportedKernelTypes;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Table-driven tests of the generated test data. Every supported type was also
 * checked to compile against the TornadoVM 7.3.1 API jar.
 */
public class ParameterInitializerTest {

    private static final String API = "uk.ac.manchester.tornado.api.";
    private static final String INT = "\\d+";
    private static final String LONG = "\\d+L";
    private static final String FLOAT = "[0-9.E-]+f";
    private static final String DOUBLE = "[0-9.E-]+";
    private static final String HALF = "new HalfFloat\\(" + FLOAT + "\\)";

    // type, expected declaration (regex, with the parameter named 'p' and a size of 8)
    private static final String[][] SUPPORTED = {
            {"int", "int p=8;"},
            {"long", "long p=" + LONG + ";"},
            {"short", "short p=\\(short\\)" + INT + ";"},
            {"byte", "byte p=\\(byte\\)" + INT + ";"},
            {"char", "char p='[a-z]';"},
            {"boolean", "boolean p=(true|false);"},
            {"float", "float p=" + FLOAT + ";"},
            {"double", "double p=" + DOUBLE + ";"},
            {API + "types.HalfFloat", API + "types.HalfFloat p= new " + API + "types.HalfFloat\\(" + FLOAT + "\\);"},
            {"int[]", "int\\[] p=\\{" + INT + "(," + INT + "){7}};"},
            {"long[]", "long\\[] p=\\{" + LONG + "(," + LONG + "){7}};"},
            {"short[]", "short\\[] p=\\{\\(short\\)" + INT + "(,\\(short\\)" + INT + "){7}};"},
            {"char[]", "char\\[] p=\\{'[a-z]'(,'[a-z]'){7}};"},
            {"boolean[]", "boolean\\[] p=\\{(true|false)(,(true|false)){7}};"},
            {"double[]", "double\\[] p=\\{" + DOUBLE + "(," + DOUBLE + "){7}};"},
            {API + "types.arrays.IntArray", API + "types.arrays.IntArray p= new " + API + "types.arrays.IntArray\\(8\\);p\\.init\\(" + INT + "\\);"},
            {"IntArray", "IntArray p= new IntArray\\(8\\);p\\.init\\(" + INT + "\\);"},
            {"LongArray", "LongArray p= new LongArray\\(8\\);p\\.init\\(" + LONG + "\\);"},
            {"ShortArray", "ShortArray p= new ShortArray\\(8\\);p\\.init\\(\\(short\\)" + INT + "\\);"},
            {"HalfFloatArray", "HalfFloatArray p= new HalfFloatArray\\(8\\);p\\.init\\(" + HALF + "\\);"},
            {"Matrix2DFloat4", "Matrix2DFloat4 p = new Matrix2DFloat4\\(8,8\\);\np\\.fill\\(" + FLOAT + "\\);"},
            {"Matrix3DShort", "Matrix3DShort p = new Matrix3DShort\\(8,8,8\\);\np\\.fill\\(\\(short\\)" + INT + "\\);"},
            {"Matrix4x4Float", "Matrix4x4Float p = new Matrix4x4Float\\(\\);\np\\.fill\\(" + FLOAT + "\\);"},
            {"ImageByte4", "ImageByte4 p = new ImageByte4\\(8,8\\);\np\\.fill\\(\\(byte\\)" + INT + "\\);"},
            {"VectorDouble4", "VectorDouble4 p = new VectorDouble4\\(8\\);\np\\.fill\\(" + FLOAT + "\\);"},
            {"VectorHalf8", "VectorHalf8 p = new VectorHalf8\\(8\\);\np\\.fill\\(" + HALF + "\\);"},
            {"Int2", "Int2 p = new Int2\\(" + INT + "," + INT + "\\);"},
            {"Int16", "Int16 p = new Int16\\(" + INT + "(," + INT + "){15}\\);"},
            {"Float16", "Float16 p = new Float16\\(" + FLOAT + "(," + FLOAT + "){15}\\);"},
            {"Double16", "Double16 p = new Double16\\(" + DOUBLE + "(," + DOUBLE + "){15}\\);"},
            {"Half16", "Half16 p = new Half16\\(" + HALF + "(," + HALF + "){15}\\);"},
            {API + "types.vectors.Half4", API + "types.vectors.Half4 p = new " + API + "types.vectors.Half4\\(" + HALF + "(," + HALF + "){3}\\);"},
            {"Byte3", "Byte3 p = new Byte3\\(\\(byte\\)" + INT + "(,\\(byte\\)" + INT + "){2}\\);"},
            {"Short2", "Short2 p = new Short2\\(\\(short\\)" + INT + ",\\(short\\)" + INT + "\\);"},
            {API + "KernelContext", API + "KernelContext p = new " + API + "KernelContext\\(\\);"},
            {API + "tile.TileContext", API + "tile.TileContext p = new " + API + "tile.TileContext\\(\\);"},
    };

    private static final String[] UNSUPPORTED = {
            "java.lang.String", "Byte2", "Short4", "Int5", "Matrix8x8Float", "com.example.Particle", "int[][]", "Float"};

    // Package prefixes of supported-kernel-types.txt: they accept an open set of types,
    // whose test data is covered type by type in SUPPORTED above
    private static final List<String> PACKAGE_PREFIXES = List.of(API);

    // Accepted by the static rule through a prefix, but with no test data: the harness
    // reports them as unsupported parameters instead of generating broken code
    private static final List<String> NO_TEST_DATA = List.of(
            "int[][]", "float[][]", "java.util.concurrent.atomic.AtomicIntegerArray");

    private static String declare(String type) throws UnsupportedParameterTypeException {
        return new ParameterInitializer(8, new Random(3)).declaration(new Parameter("p", type, false, null));
    }

    @Test
    public void supportedTypes() throws UnsupportedParameterTypeException {
        for (String[] row : SUPPORTED) {
            String declaration = declare(row[0]);
            if (!Pattern.compile("\t\t" + row[1]).matcher(declaration).matches()) {
                fail(row[0] + ": unexpected declaration\n" + declaration);
            }
        }
    }

    @Test
    public void unsupportedTypesAreRejected() {
        for (String type : UNSUPPORTED) {
            UnsupportedParameterTypeException e = assertThrows(type, UnsupportedParameterTypeException.class, () -> declare(type));
            assertEquals(type, e.type());
            assertEquals("unsupported parameter type " + type + " (parameter 'p')", e.getMessage());
        }
    }

    @Test
    public void everySupportedKernelTypeHasTestData() {
        SupportedKernelTypes types = SupportedKernelTypes.get();
        for (String name : types.names()) {
            assertGenerates(name);
        }
        for (String prefix : types.prefixes()) {
            if (prefix.endsWith(".")) {
                assertTrue("package prefix " + prefix + " needs test data rows in SUPPORTED and an entry in PACKAGE_PREFIXES",
                        PACKAGE_PREFIXES.contains(prefix));
            } else {
                assertGenerates(prefix);
            }
        }
        for (String[] row : SUPPORTED) {
            String type = row[0];
            if (type.contains(".") || type.matches("[a-z]+(\\[])?")) {
                assertTrue(type + " has test data but is not a supported kernel type", accepted(types, type));
            }
        }
    }

    @Test
    public void typesWithoutTestDataAreRejected() {
        SupportedKernelTypes types = SupportedKernelTypes.get();
        for (String type : NO_TEST_DATA) {
            assertTrue(type + " is not a supported kernel type; drop it from NO_TEST_DATA", accepted(types, type));
            assertThrows(type, UnsupportedParameterTypeException.class, () -> declare(type));
        }
    }

    private static void assertGenerates(String type) {
        if (NO_TEST_DATA.contains(type)) return;
        try {
            assertFalse(type, declare(type).isEmpty());
        } catch (UnsupportedParameterTypeException e) {
            fail(type + " is a supported kernel type without test data: add a generator to ParameterInitializer"
                    + " or list it in NO_TEST_DATA");
        }
    }

    // The resource rule on canonical text (names match the whole text, prefixes its start)
    private static boolean accepted(SupportedKernelTypes types, String type) {
        return types.names().contains(type) || types.prefixes().stream().anyMatch(type::startsWith);
    }

    @Test
    public void intParameters() throws UnsupportedParameterTypeException {
        ParameterInitializer initializer = new ParameterInitializer(128, new Random(3));
        assertEquals("\t\tint size=SIZE;", initializer.declaration(new Parameter("size", "int", false, "SIZE")));
        assertEquals("\t\tint n=128;", initializer.declaration(new Parameter("n", "int", false, null)));
        String local = initializer.declaration(new Parameter("tile", "int", true, "TILE"));
        assertTrue(local, local.matches("\t\tint tile=([1-9]|1[0-6]);"));
    }

    @Test
    public void generatorReportsTheUnsupportedParameter() {
        HarnessModel model = HarnessGeneratorTest.model(List.of(
                new Parameter("a", "FloatArray", false, null),
                new Parameter("name", "java.lang.String", false, null)), null);
        UnsupportedParameterTypeException e = assertThrows(UnsupportedParameterTypeException.class,
                () -> HarnessGenerator.generate(model, 8, new Random(1)));
        assertEquals("name", e.parameterName());
    }
}
