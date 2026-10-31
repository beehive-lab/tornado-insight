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

import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The harnesses that run a kernel and the original method on the same data. Set
 * {@code INSIGHT_HARNESS_DUMP} to a file path ending in {@code .java} to write each harness out,
 * to compile and run it by hand against a TornadoVM SDK.
 */
public class VerificationHarnessGeneratorTest extends TornadoTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    private PsiMethod method(String className, String name) {
        return myFixture.findClass(className).findMethodsByName(name, false)[0];
    }

    private static List<VerificationModel.Parameter> parameters(PsiMethod method) {
        List<VerificationModel.Parameter> parameters = new ArrayList<>();
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            parameters.add(new VerificationModel.Parameter(parameter.getName(), parameter.getType().getCanonicalText()));
        }
        return parameters;
    }

    // Built as AI-assisted transformation builds it: only the fields the copied code uses
    private VerificationModel model(PsiMethod kernel, PsiMethod original, List<String> grid) {
        return new VerificationModel(HarnessModelBuilder.build(kernel, "InsightVerify", false), parameters(kernel),
                HarnessModelBuilder.build(original, VerificationHarnessGenerator.ORIGINAL_CLASS, false), parameters(original),
                original.hasModifierProperty("static"), original.getReturnType().getCanonicalText(), grid, List.of());
    }

    private static String generate(VerificationModel model, String dumpSuffix) throws Exception {
        String harness = VerificationHarnessGenerator.generate(model).source();
        dump(harness, dumpSuffix);
        return harness;
    }

    private static void dump(String harness, String suffix) throws IOException {
        String dump = System.getenv("INSIGHT_HARNESS_DUMP");
        if (dump != null) {
            Files.writeString(Path.of(dump.replace(".java", suffix + ".java")), harness);
        }
    }

    public void testALoopParallelKernelIsRunAgainstTheOriginal() throws Exception {
        myFixture.configureByText("Saxpy.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                public class Saxpy {
                    static void saxpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                }
                class Kernel {
                    public static void saxpy(float a, float[] x, float[] y) {
                        for (@Parallel int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                }
                """);
        String harness = generate(model(method("Kernel", "saxpy"), method("Saxpy", "saxpy"), List.of()), "");
        assertTrue(harness, harness.contains("static final class InsightOriginal"));
        assertTrue(harness, harness.contains(".task(\"kernel\", InsightVerify::saxpy, a, x, y)"));
        assertTrue(harness, harness.contains("InsightOriginal.saxpy(a, insightRef1, insightRef2)"));
    }

    public void testTheReturnedValueIsComparedWithTheResultParameter() throws Exception {
        myFixture.configureByText("Dot.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.annotations.Reduce;
                public class Dot {
                    static float dot(float[] x, float[] y) {
                        float sum = 0;
                        for (int i = 0; i < x.length; i++) {
                            sum += x[i] * y[i];
                        }
                        return sum;
                    }
                }
                class Kernel {
                    public static void dot(float[] x, float[] y, @Reduce float[] result) {
                        result[0] = 0.0f;
                        for (@Parallel int i = 0; i < x.length; i++) {
                            result[0] += x[i] * y[i];
                        }
                    }
                }
                """);
        String harness = generate(model(method("Kernel", "dot"), method("Dot", "dot"), List.of()), "Dot");
        assertTrue(harness, harness.contains("insightExpected = InsightOriginal.dot(insightRef0, insightRef1);"));
        // A single value: the result array has one element
        assertTrue(harness, harness.contains("result = new float[1];"));
        assertTrue(harness, harness.contains("insightCompare(\"the returned value (in result)\", insightExpected, result, false)"));
    }

    public void testAKernelApiKernelIsRunOnItsGrid() throws Exception {
        myFixture.configureByText("Saxpy.java", """
                import uk.ac.manchester.tornado.api.KernelContext;
                public class Saxpy {
                    static void saxpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                }
                class Kernel {
                    public static void saxpy(KernelContext context, float a, float[] x, float[] y) {
                        int i = context.globalIdx;
                        if (i < x.length) {
                            y[i] += a * x[i];
                        }
                    }
                }
                """);
        String harness = generate(model(method("Kernel", "saxpy"), method("Saxpy", "saxpy"), List.of("x.length")), "Ka");
        assertTrue(harness, harness.contains("new uk.ac.manchester.tornado.api.KernelContext()"));
        assertTrue(harness, harness.contains("new uk.ac.manchester.tornado.api.WorkerGrid1D((int) (x.length))"));
        assertTrue(harness, harness.contains(".task(\"kernel\", InsightVerify::saxpy, context, a, x, y)"));
        assertTrue(harness, harness.contains("InsightOriginal.saxpy(a, insightRef2, insightRef3)"));
    }

    public void testATileApiKernelIsRunOnItsBlocks() throws Exception {
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.tile;
                public class Tile { }""");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.tile;
                public class PartitionView {
                    public Tile loadMasked(int block) { return null; }
                    public void storeMasked(Tile tile, int block) { }
                }""");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.tile;
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
                public class TileContext {
                    public int bidX() { return 0; }
                    public Object view(FloatArray a, int n) { return null; }
                    public PartitionView partition(Object view, int tile) { return null; }
                    public Tile add(Tile a, Tile b) { return null; }
                    public Tile scale(Tile a, double s) { return null; }
                }""");
        myFixture.configureByText("Saxpy.java", """
                import uk.ac.manchester.tornado.api.tile.PartitionView;
                import uk.ac.manchester.tornado.api.tile.TileContext;
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
                public class Saxpy {
                    static void saxpy(float a, FloatArray x, FloatArray y) {
                        for (int i = 0; i < x.getSize(); i++) {
                            y.set(i, a * x.get(i) + y.get(i));
                        }
                    }
                }
                class Kernel {
                    public static void saxpy(TileContext tc, float a, FloatArray x, FloatArray y) {
                        PartitionView xv = tc.partition(tc.view(x, x.getSize()), 256);
                        PartitionView yv = tc.partition(tc.view(y, y.getSize()), 256);
                        int block = tc.bidX();
                        yv.storeMasked(tc.add(tc.scale(xv.loadMasked(block), a), yv.loadMasked(block)), block);
                    }
                }
                """);
        String harness = generate(model(method("Kernel", "saxpy"), method("Saxpy", "saxpy"),
                List.of("(x.getSize() + 255) / 256")), "Tile");
        assertTrue(harness, harness.contains("new uk.ac.manchester.tornado.api.tile.TileContext()"));
        assertTrue(harness, harness.contains("WorkerGrid1D((int) ((x.getSize() + 255) / 256))"));
    }

    // Parameters are locals of the harness's main, which must not declare the same names
    public void testParametersNamedLikeTheHarnessLocalsCompile() throws Exception {
        myFixture.configureByText("Scale.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                public class Scale {
                    static void scale(float[] args, float[] e) {
                        for (int i = 0; i < args.length; i++) {
                            e[i] = 2 * args[i];
                        }
                    }
                }
                class Kernel {
                    public static void scale(float[] args, float[] e) {
                        for (@Parallel int i = 0; i < args.length; i++) {
                            e[i] = 2 * args[i];
                        }
                    }
                }
                """);
        String harness = generate(model(method("Kernel", "scale"), method("Scale", "scale"), List.of()), "Scale");
        assertTrue(harness, harness.contains("float[] args = "));
        assertTrue(harness, harness.contains("float[] e = "));
        assertFalse(harness, harness.contains("String[] args"));
        assertFalse(harness, harness.contains("Throwable e)"));
    }

    // Only the fields the code uses are copied: the others' initializers would run unchecked
    public void testOnlyTheFieldsTheKernelUsesAreCopied() {
        myFixture.configureByText("Saxpy.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                public class Saxpy {
                    static final float SCALE = 2;
                    static final Object CACHE = new Object();
                    static void saxpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                    public static void kernel(float a, float[] x, float[] y) {
                        for (@Parallel int i = 0; i < x.length; i++) {
                            y[i] += SCALE * a * x[i];
                        }
                    }
                }
                """);
        VerificationModel model = model(method("Saxpy", "kernel"), method("Saxpy", "saxpy"), List.of());
        assertEquals(List.of("SCALE"), model.kernel().fields().stream().map(HarnessModel.Field::name).toList());
        assertEmpty(model.original().fields());
    }
}
