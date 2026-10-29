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

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.List;

/**
 * The harness imports, in a project with a JDK: those of the JDK and of the TornadoVM
 * jars on the harness classpath are kept, any other is dropped.
 */
public class HarnessModelBuilderImportsTest extends TornadoTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    public void testImportsOnlyWhatTheHarnessClasspathHas() {
        PsiFile file = myFixture.configureByText("Kernels.java", """
                import java.util.List;
                import java.util.concurrent.*;
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.types.arrays.IntArray;
                import uk.ac.manchester.tornado.mlx.Mlx;
                import uk.ac.manchester.tornado.mlx.provider.*;

                class Kernels {
                    static void fill(IntArray a) {
                        for (@Parallel int i = 0; i < a.getSize(); i++) { a.set(i, i); }
                    }
                }""");
        PsiMethod kernel = TornadoKernels.findKernels(file).get(0);

        // tornado-mlx is not on the harness classpath, so javac would reject its imports
        assertEquals(List.of("import java.util.List;", "import java.util.concurrent.*;",
                        "import uk.ac.manchester.tornado.api.annotations.Parallel;",
                        "import uk.ac.manchester.tornado.api.types.arrays.IntArray;"),
                HarnessModelBuilder.build(kernel, "fillAb12C").imports());
    }
}
