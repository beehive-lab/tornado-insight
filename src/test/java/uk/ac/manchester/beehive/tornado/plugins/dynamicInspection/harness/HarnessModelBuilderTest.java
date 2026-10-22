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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness.HarnessModel.Parameter;
import uk.ac.manchester.beehive.tornado.plugins.inspector.TornadoKernels;

import java.util.List;

public class HarnessModelBuilderTest extends TornadoTestCase {

    private static final String INT_ARRAY = "uk.ac.manchester.tornado.api.types.arrays.IntArray";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    private HarnessModel build(String fileName, String text, String kernelName) {
        PsiFile file = myFixture.configureByText(fileName, text);
        PsiMethod kernel = TornadoKernels.findKernels(file).stream()
                .filter(m -> m.getName().equals(kernelName))
                .findFirst().orElseThrow();
        return HarnessModelBuilder.build(kernel, kernelName + "Ab12C");
    }

    public void testParameterTypesAreCanonical() {
        HarnessModel model = build("Kernels.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.types.arrays.IntArray;

                class Kernels {
                    static void copy(uk.ac.manchester.tornado.api.types.arrays.IntArray a, final IntArray b, int n) {
                        for (@Parallel int i = 0; i < n; i++) { b.set(i, a.get(i)); }
                    }
                }""", "copy");

        assertEquals(List.of(
                new Parameter("a", INT_ARRAY, false, null),
                new Parameter("b", INT_ARRAY, false, null),
                new Parameter("n", "int", false, null)), model.parameters());
        assertEquals(TornadoKernels.Kind.PARALLEL_LOOP, model.kind());
    }
}
