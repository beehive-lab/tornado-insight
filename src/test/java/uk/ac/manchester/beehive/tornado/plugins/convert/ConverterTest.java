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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmRequest;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmResponse;
import uk.ac.manchester.beehive.tornado.plugins.ai.ModelInfo;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationHarnessGenerator;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationModel;

import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

public class ConverterTest extends TornadoTestCase {

    private static final String SOURCE = """
            public class Saxpy {
                static void sa<caret>xpy(float a, float[] x, float[] y) {
                    for (int i = 0; i < x.length; i++) {
                        y[i] += a * x[i];
                    }
                }
            }
            """;

    private static final String GOOD = """
            Here it is:
            ```java
            public static void saxpy(float a, float[] x, float[] y) {
                for (@Parallel int i = 0; i < x.length; i++) {
                    y[i] += a * x[i];
                }
            }
            ```
            The loop over i is parallel.""";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        myFixture.configureByText("Saxpy.java", SOURCE);
    }

    public void testGoodAnswerPassesInOneRoundAndImportsParallel() throws Exception {
        ScriptedModel model = new ScriptedModel(GOOD);
        Converter.Result result = convert(model, false);
        assertEmpty(result.problems());
        assertEquals(1, result.rounds());
        assertFalse(result.repaired());
        assertEquals("saxpy", result.methodName());
        assertEquals(0, result.callers());
        assertEquals("The loop over i is parallel.", result.explanation().replace("Here it is:", "").strip());
        String preview = preview(result);
        assertTrue(preview, preview.contains("import uk.ac.manchester.tornado.api.annotations.Parallel;"));
        assertTrue(preview, preview.contains("static void saxpy(float a"));
        assertTrue(result.kernelText(), result.kernelText().contains("for (@Parallel int i = 0;"));
        // The kernel replaces the method and keeps its access modifier
        assertFalse(preview, preview.contains("for (int i"));
        assertTrue(result.kernelText(), result.kernelText().startsWith("static void saxpy("));
        // The editor's file is not changed by the check
        assertFalse(myFixture.getFile().getText().contains("@Parallel"));
        // The first request: rules as the system prompt, the method in the message, no schema
        LlmRequest first = model.requests.get(0);
        assertTrue(first.system().contains("`@Parallel` goes on the index variable"));
        assertFalse(first.system().contains("check:"));
        assertTrue(first.messages().get(0).text().contains("named `saxpy`"));
        assertTrue(first.messages().get(0).text().contains("y[i] += a * x[i];"));
        assertNull(first.jsonSchema());
    }

    public void testParallelOnTheMethodIsMovedToTheLoop() throws Exception {
        Converter.Result result = convert(new ScriptedModel("""
                ```java
                @Parallel
                public static void saxpy(float a, float[] x, float[] y) {
                    for (int i = 0; i < x.length; i++) {
                        y[i] += a * x[i];
                    }
                }
                ```"""), false);
        assertEmpty(result.problems());
        assertEquals(1, result.rounds());
        assertTrue(result.repaired());
        assertTrue(result.kernelText(), result.kernelText().contains("for (@Parallel int i = 0;"));
        assertFalse(result.kernelText(), result.kernelText().startsWith("@Parallel"));
    }

    public void testProblemsAreSentBackAndTheSecondAnswerIsUsed() throws Exception {
        ScriptedModel model = new ScriptedModel("""
                ```java
                public static void saxpy(float a, float[] x, float[] y) {
                    for (@Parallel int i = 0; i < x.length; i++) {
                        assert a != 0;
                        y[i] += a * x[i];
                    }
                }
                ```""", GOOD);
        Converter.Result result = convert(model, false);
        assertEmpty(result.problems());
        assertEquals(2, result.rounds());
        List<LlmRequest.Message> second = model.requests.get(1).messages();
        assertEquals(3, second.size());
        assertEquals(LlmRequest.Role.ASSISTANT, second.get(1).role());
        assertTrue(second.get(2).text(), second.get(2).text().contains("assert"));
    }

    public void testChangedParametersAreRejected() throws Exception {
        String wrong = """
                ```java
                public static void saxpy(float a, FloatArray x, FloatArray y) {
                    for (@Parallel int i = 0; i < x.getSize(); i++) {
                        y.set(i, y.get(i) + a * x.get(i));
                    }
                }
                ```""";
        Converter.Result result = convert(new ScriptedModel(wrong, wrong, wrong), false);
        assertEquals(Converter.MAX_ROUNDS, result.rounds());
        assertTrue(result.problems().toString(), result.problems().toString().contains("must keep the type `float[]`"));
        // The last candidate is still shown
        assertTrue(preview(result).contains("import uk.ac.manchester.tornado.api.types.arrays.FloatArray;"));
    }

    public void testNoParallelLoopIsAProblem() throws Exception {
        String sequential = """
                ```java
                public static void saxpy(float a, float[] x, float[] y) {
                    for (int i = 0; i < x.length; i++) {
                        y[i] += a * x[i];
                    }
                }
                ```""";
        Converter.Result result = convert(new ScriptedModel(sequential, sequential, sequential), false);
        assertTrue(result.problems().toString(), result.problems().toString().contains("no `@Parallel` loop"));
    }

    public void testNoCodeInAnyAnswer() {
        try {
            convert(new ScriptedModel("I cannot.", "Still no.", "No."), false);
            fail();
        } catch (Converter.ConversionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("3"));
        } catch (LlmException e) {
            fail(e.toString());
        }
    }

    public void testStructuredAnswer() throws Exception {
        ScriptedModel model = new ScriptedModel("""
                {"code": "public static void saxpy(float a, float[] x, float[] y) { for (@Parallel int i = 0; i < x.length; i++) { y[i] += a * x[i]; } }",
                 "explanation": "Parallel over i."}""");
        Converter.Result result = convert(model, true);
        assertEmpty(result.problems());
        assertEquals("Parallel over i.", result.explanation());
        assertNotNull(model.requests.get(0).jsonSchema());
    }

    public void testReturningMethodMayAddAResultParameter() throws Exception {
        myFixture.configureByText("Dot.java", """
                public class Dot {
                    static float d<caret>ot(float[] x, float[] y) {
                        float sum = 0;
                        for (int i = 0; i < x.length; i++) {
                            sum += x[i] * y[i];
                        }
                        return sum;
                    }

                    static float norm(float[] x) {
                        return (float) Math.sqrt(dot(x, x));
                    }
                }
                """);
        Converter.Result result = convert(new ScriptedModel("""
                ```java
                public static void dot(float[] x, float[] y, @Reduce float[] result) {
                    result[0] = 0.0f;
                    for (@Parallel int i = 0; i < x.length; i++) {
                        result[0] += x[i] * y[i];
                    }
                }
                ```"""), false);
        assertEmpty(result.problems());
        assertTrue(preview(result).contains("import uk.ac.manchester.tornado.api.annotations.Reduce;"));
        // norm's call no longer matches
        assertEquals(1, result.callers());
    }

    public void testOtherNameIsRenamedAndTheDocCommentKept() throws Exception {
        myFixture.configureByText("Fill.java", """
                public class Fill {
                    /** Sets every element to v. */
                    private static void f<caret>ill(float[] a, float v) {
                        for (int i = 0; i < a.length; i++) {
                            a[i] = v;
                        }
                    }
                }
                """);
        Converter.Result result = convert(new ScriptedModel("""
                ```java
                public static void fillKernel(float[] a, float v) {
                    for (@Parallel int i = 0; i < a.length; i++) {
                        a[i] = v;
                    }
                }
                ```"""), false);
        assertEmpty(result.problems());
        // The doc comment is the method's, so it stays with the method: the kernel text has none
        assertTrue(result.kernelText(), result.kernelText().startsWith("private static void fill(float[] a, float v)"));
        assertTrue(preview(result), preview(result).contains("/** Sets every element to v. */\n    private static void fill("));
        assertFalse(preview(result), preview(result).contains("fillKernel"));
        assertAcceptMatchesThePreview(result);
    }

    public void testAcceptGivesTheSameTextAsThePreview() throws Exception {
        assertAcceptMatchesThePreview(convert(new ScriptedModel(GOOD), false));
    }

    public void testAKernelAddedBesideKeepsTheMethodAndItsCallers() throws Exception {
        myFixture.configureByText("Saxpy.java", """
                public class Saxpy {
                    /** Adds a times x to y. */
                    static void sa<caret>xpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }

                    static void run(float[] x, float[] y) {
                        saxpy(2, x, y);
                    }
                }
                """);
        Converter.Result result = convert(new ScriptedModel(KERNEL_API), candidate ->
                new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null), Target.KERNEL_API);
        assertEquals("saxpyKernel", KernelReplacement.besideName(methodAtCaret()));
        String beside = KernelReplacement.preview(methodAtCaret(), result.kernelText(), KernelReplacement.Placement.BESIDE);
        // The method, its doc comment and its caller are unchanged, and the kernel follows it
        assertTrue(beside, beside.contains("""
                    /** Adds a times x to y. */
                    static void saxpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }

                    static void saxpyKernel(KernelContext context, float a, float[] x, float[] y) {
                """));
        assertTrue(beside, beside.contains("saxpy(2, x, y);"));
        assertEquals(beside, 1, beside.split("Adds a times x to y", -1).length - 1);
        assertTrue(beside, beside.contains("import uk.ac.manchester.tornado.api.KernelContext;"));
        assertAcceptMatchesThePreview(result, KernelReplacement.Placement.BESIDE);
    }

    public void testTheKernelAddedBesideGetsANameTheClassDoesNotHave() {
        myFixture.configureByText("Saxpy.java", """
                public class Saxpy {
                    static void sa<caret>xpy(float a, float[] x, float[] y) {
                    }

                    static void saxpyKernel() {
                    }

                    static void saxpyKernel2() {
                    }
                }
                """);
        assertEquals("saxpyKernel3", KernelReplacement.besideName(methodAtCaret()));
    }

    public void testAcceptGivesTheSameTextAsThePreviewAfterARepair() throws Exception {
        Converter.Result result = convert(new ScriptedModel("""
                ```java
                @uk.ac.manchester.tornado.api.annotations.Parallel
                public static void saxpy(float a, float[] x, float[] y) {
                    for (int i = 0; i < x.length; i++) {
                        y[i] += a * x[i];
                    }
                }
                ```"""), false);
        assertTrue(result.repaired());
        // The moved annotation is imported and formatted like the rest of the kernel
        assertTrue(result.kernelText(), result.kernelText().contains("for (@Parallel int i = 0;"));
        assertAcceptMatchesThePreview(result);
    }

    public void testACutOffFirstAnswerStopsTheConversion() {
        ScriptedModel model = new ScriptedModel(ScriptedModel.CUT_OFF + "{\"code\": \"public static void saxpy(");
        try {
            convert(model, false);
            fail();
        } catch (Converter.ConversionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("cut off"));
            assertEquals("not sent back to the model", 1, model.requests.size());
        } catch (LlmException e) {
            fail(e.toString());
        }
    }

    public void testACutOffLaterAnswerKeepsTheLastKernel() throws Exception {
        String sequential = """
                ```java
                public static void saxpy(float a, float[] x, float[] y) {
                    for (int i = 0; i < x.length; i++) {
                        y[i] += a * x[i];
                    }
                }
                ```""";
        Converter.Result result = convert(new ScriptedModel(sequential, ScriptedModel.CUT_OFF + "```java"), false);
        assertEquals(2, result.rounds());
        assertTrue(result.problems().toString(), result.problems().toString().contains("no `@Parallel` loop"));
        assertTrue(result.problems().toString(), result.problems().toString().contains("cut off"));
    }

    public void testTokenLimit() {
        // No context reported: the fixed limit
        assertEquals(Integer.valueOf(8192), Converter.tokenLimit(8192, null, 30_000));
        // A large context: capped at the limit
        assertEquals(Integer.valueOf(32_768), Converter.tokenLimit(32_768, 131_072, 30_000));
        // A small context: what is left after the prompt (30000 / 3 tokens) and the template (512)
        assertEquals(Integer.valueOf(16_384 - 10_000 - 512), Converter.tokenLimit(32_768, 16_384, 30_000));
        // No room left for an answer
        assertNull(Converter.tokenLimit(32_768, 8192, 30_000));
    }

    public void testTheLimitFollowsTheServersContext() throws Exception {
        ScriptedModel model = new ScriptedModel(GOOD);
        convert(model, false, 16_384);
        LlmRequest request = model.requests.get(0);
        int prompt = request.system().length() + request.messages().get(0).text().length();
        assertEquals(16_384 - prompt / 3 - 512, request.maxTokens());
    }

    public void testAContextTooSmallForThePromptStopsBeforeAsking() {
        ScriptedModel model = new ScriptedModel(GOOD);
        try {
            convert(model, false, 2048);
            fail();
        } catch (Converter.ConversionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2,048") || e.getMessage().contains("2048"));
            assertEmpty(model.requests);
        } catch (LlmException e) {
            fail(e.toString());
        }
    }

    public void testAddingStaticCountsTheCallers() throws Exception {
        myFixture.configureByText("Scale.java", """
                public class Scale {
                    void sc<caret>ale(float[] a) {
                        for (int i = 0; i < a.length; i++) {
                            a[i] *= 2;
                        }
                    }

                    void twice(float[] a) {
                        scale(a);
                        scale(a);
                    }
                }
                """);
        Converter.Result result = convert(new ScriptedModel("""
                ```java
                public static void scale(float[] a) {
                    for (@Parallel int i = 0; i < a.length; i++) {
                        a[i] *= 2;
                    }
                }
                ```"""), false);
        assertEquals(2, result.callers());
    }

    public void testAPassingCandidateIsRunAgainstTheOriginal() throws Exception {
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(GOOD), model -> {
            runs.add(model);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertTrue(result.verified());
        assertEquals(1, runs.size());
        VerificationModel model = runs.get(0);
        assertEquals("saxpy", model.original().kernelName());
        assertEquals(List.of("float", "float[]", "float[]"),
                model.kernelParameters().stream().map(VerificationModel.Parameter::type).toList());
        assertTrue(model.originalStatic());
        assertEquals("void", model.originalReturnType());

        String harness = VerificationHarnessGenerator.generate(model).source();
        String dump = System.getenv("INSIGHT_HARNESS_DUMP");
        if (dump != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dump), harness);
        }
        assertTrue(harness, harness.contains("static final class InsightOriginal"));
        assertTrue(harness, harness.contains(".task(\"kernel\", InsightVerify::saxpy, a, x, y)"));
        assertTrue(harness, harness.contains("InsightOriginal.saxpy(a, insightRef1, insightRef2)"));
    }

    public void testTheReturnedValueIsComparedWithTheResultParameter() throws Exception {
        myFixture.configureByText("Dot.java", """
                public class Dot {
                    static float d<caret>ot(float[] x, float[] y) {
                        float sum = 0;
                        for (int i = 0; i < x.length; i++) {
                            sum += x[i] * y[i];
                        }
                        return sum;
                    }
                }
                """);
        List<VerificationModel> runs = new ArrayList<>();
        convert(new ScriptedModel("""
                ```java
                public static void dot(float[] x, float[] y, @Reduce float[] result) {
                    result[0] = 0.0f;
                    for (@Parallel int i = 0; i < x.length; i++) {
                        result[0] += x[i] * y[i];
                    }
                }
                ```"""), model -> {
            runs.add(model);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        String harness = VerificationHarnessGenerator.generate(runs.get(0)).source();
        String dump = System.getenv("INSIGHT_HARNESS_DUMP");
        if (dump != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dump.replace(".java", "Dot.java")), harness);
        }
        assertTrue(harness, harness.contains("insightExpected = InsightOriginal.dot(insightRef0, insightRef1);"));
        // A single value: the result array has one element
        assertTrue(harness, harness.contains("result = new float[1];"));
        assertTrue(harness, harness.contains("insightCompare(\"the returned value (in result)\", insightExpected, result, false)"));
    }

    public void testAKernelWithDifferentOutputsIsSentBack() throws Exception {
        ScriptedModel model = new ScriptedModel(GOOD, GOOD);
        int[] runs = {0};
        Converter.Result result = convert(model, candidate -> ++runs[0] == 1
                ? new KernelVerifier.Outcome(KernelVerifier.Status.DIFFERENT, List.of("y differs at index 3."), "4096 elements", null)
                : new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null));
        assertEquals(2, result.rounds());
        assertTrue(result.verified());
        assertTrue(model.requests.get(1).messages().get(2).text().contains("y differs at index 3."));
    }

    public void testAKernelWhoseResultsStillDifferIsNotOffered() {
        ScriptedModel model = new ScriptedModel(GOOD, GOOD, GOOD);
        try {
            convert(model, candidate -> new KernelVerifier.Outcome(KernelVerifier.Status.DIFFERENT,
                    List.of("y differs at index 3."), "4096 elements", null));
            fail("a kernel with wrong results was offered");
        } catch (Converter.ConversionException e) {
            assertFalse(e instanceof Converter.NotParallelException);
            assertTrue(e.getMessage(), e.getMessage().contains("y differs at index 3."));
        } catch (LlmException e) {
            throw new AssertionError(e);
        }
        assertEquals(3, model.requests.size());
    }

    public void testAKernelThatStillFailsIsNotOffered() {
        ScriptedModel model = new ScriptedModel(GOOD, GOOD, GOOD);
        try {
            convert(model, candidate -> new KernelVerifier.Outcome(KernelVerifier.Status.KERNEL_FAILED,
                    List.of("Line 3: cannot find symbol"), null, null));
            fail("a kernel that does not compile was offered");
        } catch (Converter.ConversionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("No working kernel after 3 answers"));
            assertTrue(e.getMessage(), e.getMessage().contains("Line 3: cannot find symbol"));
        } catch (LlmException e) {
            throw new AssertionError(e);
        }
    }

    public void testTheModelMayAnswerThatTheMethodCannotBeParallelised() {
        ScriptedModel model = new ScriptedModel(GOOD, "CANNOT PARALLELIZE: y[i] needs y[i - 1].");
        int[] runs = {0};
        try {
            convert(model, candidate -> {
                runs[0]++;
                return new KernelVerifier.Outcome(KernelVerifier.Status.DIFFERENT, List.of("y differs at index 3."),
                        "4096 elements", null);
            });
            fail("no kernel should be offered");
        } catch (Converter.NotParallelException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("y[i] needs y[i - 1]."));
        } catch (Converter.ConversionException | LlmException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, runs[0]);
        assertTrue(model.requests.get(0).system().contains("method cannot be parallelised, and why"));
        assertTrue(model.requests.get(0).messages().get(0).text().contains(Answer.NOT_PARALLEL));
    }

    public void testAKernelThatIsNotVerifiedIsStillOffered() throws Exception {
        Converter.Result result = convert(new ScriptedModel(GOOD), candidate ->
                new KernelVerifier.Outcome(KernelVerifier.Status.NOT_VERIFIED, List.of("no TornadoVM"), null, null));
        assertEquals(1, result.rounds());
        assertTrue(result.problems().isEmpty());
        assertFalse(result.verified());
    }

    private static final String KERNEL_API = """
            ```java
            public static void saxpy(KernelContext context, float a, float[] x, float[] y) {
                // grid: x.length
                int i = context.globalIdx;
                y[i] += a * x[i];
            }
            ```""";

    public void testKernelApiCandidateIsRunOnItsGrid() throws Exception {
        ScriptedModel model = new ScriptedModel(KERNEL_API);
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(model, candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        }, Target.KERNEL_API);
        assertEmpty(result.problems());
        assertTrue(result.verified());
        assertEquals(Target.KERNEL_API, result.target());
        assertTrue(model.requests.get(0).system().contains("context.globalIdx"));
        assertTrue(model.requests.get(0).messages().get(0).text().contains("uses the Kernel API"));
        assertTrue(model.requests.get(0).messages().get(0).text().contains(
                "The kernel will run on NVIDIA GeForce RTX 5080 Laptop GPU, with TornadoVM's CUDA backend."));
        assertTrue(preview(result).contains("import uk.ac.manchester.tornado.api.KernelContext;"));
        assertEquals(List.of("x.length"), runs.get(0).grid());

        String harness = VerificationHarnessGenerator.generate(runs.get(0)).source();
        String dump = System.getenv("INSIGHT_HARNESS_DUMP");
        if (dump != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dump.replace(".java", "Ka.java")), harness);
        }
        assertTrue(harness, harness.contains("new uk.ac.manchester.tornado.api.KernelContext()"));
        assertTrue(harness, harness.contains("new uk.ac.manchester.tornado.api.WorkerGrid1D((int) (x.length))"));
        assertTrue(harness, harness.contains(".task(\"kernel\", InsightVerify::saxpy, context, a, x, y)"));
        assertTrue(harness, harness.contains("InsightOriginal.saxpy(a, insightRef2, insightRef3)"));
    }

    public void testKernelApiProblems() throws Exception {
        String noGrid = """
                ```java
                public static void saxpy(KernelContext context, float a, float[] x, float[] y) {
                    int i = context.globalIdx;
                    y[i] += a * x[i];
                }
                ```""";
        String badGrid = """
                ```java
                public static void saxpy(KernelContext context, float a, float[] x, float[] y) {
                    // grid: size
                    int i = context.globalIdx;
                    y[i] += a * x[i];
                }
                ```""";
        String noContext = """
                ```java
                public static void saxpy(float a, float[] x, float[] y) {
                    // grid: x.length
                    for (@Parallel int i = 0; i < x.length; i++) {
                        y[i] += a * x[i];
                    }
                }
                ```""";
        ScriptedModel model = new ScriptedModel(noGrid, badGrid, noContext);
        Converter.Result result = convert(model, null, Target.KERNEL_API);
        assertEquals(3, result.rounds());
        assertTrue(model.requests.get(1).messages().get(2).text().contains("// grid: x.length"));
        assertTrue(model.requests.get(2).messages().get(4).text().contains("`size` in `size` is not allowed"));
        assertTrue(result.problems().toString(), result.problems().contains("The kernel must take a `KernelContext` as its first parameter."));
        assertTrue(result.problems().toString(), result.problems().stream().anyMatch(p -> p.startsWith("Do not use `@Parallel`")));
    }

    public void testANonStaticKernelIsSentBack() throws Exception {
        String instance = GOOD.replace("public static void saxpy", "public void saxpy");
        ScriptedModel model = new ScriptedModel(instance, GOOD);
        Converter.Result result = convert(model, (Converter.Verifier) null);
        assertEquals(2, result.rounds());
        assertTrue(model.requests.get(1).messages().get(2).text().contains("The kernel must be `static`."));
        assertEmpty(result.problems());
    }

    // Parameters are locals of the harness's main, which must not declare the same names
    public void testParametersNamedLikeTheHarnessLocalsCompile() throws Exception {
        myFixture.configureByText("Scale.java", """
                public class Scale {
                    static void sc<caret>ale(float[] args, float[] e) {
                        for (int i = 0; i < args.length; i++) {
                            e[i] = 2 * args[i];
                        }
                    }
                }
                """);
        String kernel = """
                ```java
                public static void scale(float[] args, float[] e) {
                    for (@Parallel int i = 0; i < args.length; i++) {
                        e[i] = 2 * args[i];
                    }
                }
                ```""";
        List<VerificationModel> runs = new ArrayList<>();
        convert(new ScriptedModel(kernel), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        String harness = VerificationHarnessGenerator.generate(runs.get(0)).source();
        assertTrue(harness, harness.contains("float[] args = "));
        assertTrue(harness, harness.contains("float[] e = "));
        assertFalse(harness, harness.contains("String[] args"));
        assertFalse(harness, harness.contains("Throwable e)"));
    }

    // The harness runs the grid sizes as Java before the kernel: only parameters and arithmetic
    public void testAGridThatRunsOtherCodeIsNeverRun() throws Exception {
        myFixture.addClass("package java.lang; public class Runtime { public static Runtime getRuntime() { return null; } "
                + "public int availableProcessors() { return 1; } }");
        String call = """
                ```java
                public static void saxpy(KernelContext context, float a, float[] x, float[] y) {
                    // grid: x.length + 0 * Runtime.getRuntime().availableProcessors()
                    int i = context.globalIdx;
                    y[i] += a * x[i];
                }
                ```""";
        String escape = call.replace("Runtime.getRuntime().availableProcessors()", "x.length\\u0029");
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(call, escape, call), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        }, Target.KERNEL_API);
        assertEmpty(runs);
        assertTrue(result.problems().toString(), result.problems().stream().anyMatch(p -> p.contains(
                "`Runtime.getRuntime().availableProcessors()` in `x.length + 0 * Runtime.getRuntime().availableProcessors()` is not allowed")));
    }

    public void testAGridMayUseLengthsSizesArithmeticAndMathMinMax() throws Exception {
        myFixture.addClass("package java.lang; public final class Math { public static int max(int a, int b) { return a; } }");
        String grid = KERNEL_API.replace("// grid: x.length", "// grid: Math.max((x.length + 255) / 256, 1) * 256, -(-y.length)");
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(grid), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        }, Target.KERNEL_API);
        assertEmpty(result.problems());
        assertEquals(List.of("Math.max((x.length + 255) / 256, 1) * 256", "-(-y.length)"), runs.get(0).grid());
    }

    // The harness runs the kernel's body: only the TornadoVM API, Math, the wrappers and project code
    public void testAKernelThatUsesOtherClassesIsNeverRun() throws Exception {
        myFixture.addClass("package java.net; public class URLClassLoader { public URLClassLoader(int seed) { } }");
        String loader = GOOD.replace("y[i] += a * x[i];", "y[i] += a * x[i]; new java.net.URLClassLoader(i);");
        // javac reads a Unicode line break escape in a comment as a line break; so does the PSI, so the checks see the call
        String escape = GOOD.replace("y[i] += a * x[i];", "y[i] += a * x[i]; // \\u000a new java.net.URLClassLoader(i);");
        String lambda = GOOD.replace("y[i] += a * x[i];", "y[i] += a * x[i]; Object f = () -> { };");
        List<VerificationModel> runs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(loader, escape, lambda);
        Converter.Result result = convert(model, candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(runs);
        assertTrue(model.requests.get(1).messages().get(2).text(),
                model.requests.get(1).messages().get(2).text().contains("`URLClassLoader` in the kernel is not allowed"));
        assertTrue(model.requests.get(2).messages().get(4).text(),
                model.requests.get(2).messages().get(4).text().contains("`URLClassLoader` in the kernel is not allowed")
                        || model.requests.get(2).messages().get(4).text().contains("Do not use Unicode escapes"));
        assertFalse(result.verified());
    }

    // TornadoVM falls back to running a kernel it cannot compile as Java, so its host API would run
    public void testAKernelThatUsesTornadoVmsHostApiIsNeverRun() throws Exception {
        addTornadoRuntime();
        String host = GOOD.replace("y[i] += a * x[i];",
                "y[i] += a * x[i] + uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.threads();");
        List<VerificationModel> runs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(host, host, host);
        Converter.Result result = convert(model, candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(runs);
        assertTrue(result.problems().toString(), result.problems().stream().anyMatch(p -> p.contains(
                "`TornadoRuntimeProvider` in the kernel is not allowed")));
    }

    // Host code of TornadoVM's API, which the kernel inspections let through
    private void addTornadoRuntime() {
        myFixture.addClass("package uk.ac.manchester.tornado.api.runtime; public class TornadoRuntimeProvider { "
                + "public static int threads() { return 1; } }");
    }

    // The harness copies the project code the kernel uses, and runs it
    public void testAKernelWhoseHelpersOrFieldsUseOtherClassesIsNeverRun() throws Exception {
        addTornadoRuntime();
        myFixture.configureByText("Saxpy.java", """
                public class Saxpy {
                    static final int OFFSET = uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.threads();
                    static void sa<caret>xpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                    static float scale(float a, float v) {
                        return a * v + uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.threads();
                    }
                }
                """);
        String helper = GOOD.replace("y[i] += a * x[i];", "y[i] += scale(a, x[i]);");
        String field = GOOD.replace("y[i] += a * x[i];", "y[i] += a * x[i] + OFFSET;");
        List<VerificationModel> runs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(helper, field, field);
        convert(model, candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(runs);
        String first = model.requests.get(1).messages().get(2).text();
        assertTrue(first, first.contains("TornadoRuntimeProvider` in `scale`, which the kernel calls, is not allowed"));
        String second = model.requests.get(2).messages().get(4).text();
        assertTrue(second, second.contains("in the value of `OFFSET`, which the kernel uses, is not allowed"));
    }

    // The candidate is checked in a copy of the file, whose helpers are still the project's code
    public void testAKernelMayCallAHelperOfItsClass() throws Exception {
        myFixture.configureByText("Saxpy.java", """
                public class Saxpy {
                    static final float SCALE = 2;
                    static void sa<caret>xpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                    static float scale(float a, float v) {
                        return a * v * SCALE;
                    }
                }
                """);
        String helper = GOOD.replace("y[i] += a * x[i];", "y[i] += scale(a, x[i]);");
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(helper), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(result.problems());
        assertEquals(1, runs.size());
        assertEquals(1, runs.get(0).kernel().helperSources().size());
        assertEquals(List.of("SCALE"), runs.get(0).kernel().fields().stream().map(f -> f.name()).toList());
    }

    // The harness copies the project's static methods and fields, not its classes
    public void testAKernelUsingProjectClassesIsNotRunAndNotBlamed() throws Exception {
        myFixture.configureByText("Saxpy.java", """
                public class Saxpy {
                    enum Mode { FAST, EXACT }
                    static final int MODE = 1;
                    static void sa<caret>xpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += Mode.FAST == Mode.EXACT ? x[i] : a * x[i];
                        }
                    }
                }
                """);
        String kernel = """
                ```java
                public static void saxpy(float a, float[] x, float[] y) {
                    for (@Parallel int i = 0; i < x.length; i++) {
                        y[i] += Mode.FAST == Mode.EXACT ? x[i] : a * x[i];
                    }
                }
                ```""";
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(kernel), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.KERNEL_FAILED, List.of("javac: Mode"), null, null);
        });
        assertEmpty(runs);
        assertEmpty(result.problems());
        assertEquals(1, result.rounds());
        assertEquals(KernelVerifier.Status.NOT_VERIFIED, result.outcome().status());
        assertTrue(result.outcome().details().toString(), result.outcome().details().get(0).contains("`Mode.FAST`"));
    }

    public void testAKernelWithStringsIsNeverRun() throws Exception {
        myFixture.addClass("package java.lang; public final class String { }");
        myFixture.addClass("package java.lang; public final class Integer { public static int parseInt(String s) { return 0; } }");
        String text = GOOD.replace("y[i] += a * x[i];", "y[i] += a * x[i] + Integer.parseInt(\"1\");");
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(text, text, text), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(runs);
        assertTrue(result.problems().toString(), result.problems().contains("Do not use strings in the kernel."));
    }

    // Only the fields the code uses are copied: the others' initializers would run unchecked
    public void testTheHarnessCopiesOnlyTheFieldsTheKernelUses() throws Exception {
        myFixture.configureByText("Saxpy.java", """
                public class Saxpy {
                    static final float SCALE = 2;
                    static final Object CACHE = new Object();
                    static void sa<caret>xpy(float a, float[] x, float[] y) {
                        for (int i = 0; i < x.length; i++) {
                            y[i] += a * x[i];
                        }
                    }
                }
                """);
        String scaled = GOOD.replace("y[i] += a * x[i];", "y[i] += SCALE * a * x[i];");
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel(scaled), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(result.problems());
        assertEquals(List.of("SCALE"), runs.get(0).kernel().fields().stream().map(f -> f.name()).toList());
        assertEmpty(runs.get(0).original().fields());
    }

    // A Loop Parallel kernel has no grid, whatever its comments say
    public void testALoopParallelKernelIgnoresGridComments() throws Exception {
        String commented = GOOD.replace("for (@Parallel", "// grid: x.length + 1\n    for (@Parallel");
        List<VerificationModel> runs = new ArrayList<>();
        convert(new ScriptedModel(commented), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        });
        assertEmpty(runs.get(0).grid());
    }

    public void testTileApiCandidateIsRunOnItsBlocks() throws Exception {
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
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
                public class Saxpy {
                    static void sa<caret>xpy(float a, FloatArray x, FloatArray y) {
                        for (int i = 0; i < x.getSize(); i++) {
                            y.set(i, a * x.get(i) + y.get(i));
                        }
                    }
                }
                """);
        List<VerificationModel> runs = new ArrayList<>();
        Converter.Result result = convert(new ScriptedModel("""
                ```java
                public static void saxpy(TileContext tc, float a, FloatArray x, FloatArray y) {
                    // grid: (x.getSize() + 255) / 256
                    PartitionView xv = tc.partition(tc.view(x, x.getSize()), 256);
                    PartitionView yv = tc.partition(tc.view(y, y.getSize()), 256);
                    int block = tc.bidX();
                    yv.storeMasked(tc.add(tc.scale(xv.loadMasked(block), a), yv.loadMasked(block)), block);
                }
                ```"""), candidate -> {
            runs.add(candidate);
            return new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        }, Target.TILE_API);
        assertEmpty(result.problems());
        assertTrue(preview(result).contains("import uk.ac.manchester.tornado.api.tile.TileContext;"));
        String harness = VerificationHarnessGenerator.generate(runs.get(0)).source();
        String dump = System.getenv("INSIGHT_HARNESS_DUMP");
        if (dump != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dump.replace(".java", "Tile.java")), harness);
        }
        assertTrue(harness, harness.contains("new uk.ac.manchester.tornado.api.tile.TileContext()"));
        assertTrue(harness, harness.contains("WorkerGrid1D((int) ((x.getSize() + 255) / 256))"));
    }

    public void testAnAllowedMethodWithWrongArgumentsIsNotCalledForbidden() throws Exception {
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
                    public Tile mul(Tile a, Tile b) { return null; }
                    public Tile scale(Tile a, double s) { return null; }
                }""");
        myFixture.configureByText("Saxpy.java", """
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
                public class Saxpy {
                    static void sa<caret>xpy(float a, FloatArray x, FloatArray y) {
                        for (int i = 0; i < x.getSize(); i++) {
                            y.set(i, a * x.get(i) + y.get(i));
                        }
                    }
                }
                """);
        // mul takes two tiles, not a float: the model must hear that, not that mul is forbidden
        String wrong = """
                ```java
                public static void saxpy(TileContext tc, float a, FloatArray x, FloatArray y) {
                    // grid: (x.getSize() + 255) / 256
                    PartitionView xv = tc.partition(tc.view(x, x.getSize()), 256);
                    PartitionView yv = tc.partition(tc.view(y, y.getSize()), 256);
                    int block = tc.bidX();
                    yv.storeMasked(tc.add(tc.mul(xv.loadMasked(block), a), yv.loadMasked(block)), block);
                }
                ```""";
        Converter.Result result = convert(new ScriptedModel(wrong, wrong, wrong), candidate -> {
            throw new AssertionError("a kernel with problems was run");
        }, Target.TILE_API);
        String problems = result.problems().toString();
        assertTrue(problems, problems.contains("match none of the `mul` methods of `TileContext`, which take: mul(Tile, Tile)"));
        assertFalse(problems, problems.contains("is not allowed"));
    }

    public void testTheTileApiNeedsTornadoArrays() {
        assertFalse(Target.TILE_API.accepts(List.of("float", "float[]")));
        assertTrue(Target.TILE_API.accepts(List.of("float", "uk.ac.manchester.tornado.api.types.arrays.FloatArray")));
        assertTrue(Target.KERNEL_API.accepts(List.of("float[]")));
        assertEquals(List.of(Target.LOOP_PARALLEL, Target.KERNEL_API), Target.compared(java.util.EnumSet.allOf(Target.class), false));
        assertEquals(List.of(Target.LOOP_PARALLEL, Target.KERNEL_API, Target.TILE_API),
                Target.compared(java.util.EnumSet.allOf(Target.class), true));
    }

    public void testATargetWhoseContextTheProjectLacksIsNotOffered() {
        List<String> tornadoArrays = List.of("float", "uk.ac.manchester.tornado.api.types.arrays.FloatArray");
        // An older TornadoVM: KernelContext, but no TileContext
        java.util.function.Predicate<String> older = name -> !name.endsWith(".TileContext");
        assertNull(Target.LOOP_PARALLEL.unavailable(tornadoArrays, older));
        assertNull(Target.KERNEL_API.unavailable(tornadoArrays, older));
        assertEquals("the project's TornadoVM API has no TileContext, which TornadoVM 7.0.0 and newer have",
                Target.TILE_API.unavailable(tornadoArrays, older));
        assertEquals("the project's TornadoVM API has no KernelContext; it needs a newer TornadoVM",
                Target.KERNEL_API.unavailable(tornadoArrays, name -> false));
        assertNull(Target.TILE_API.unavailable(tornadoArrays, name -> true));
        assertTrue(Target.TILE_API.unavailable(List.of("float[]"), name -> true).startsWith("the method's arrays are Java arrays"));
        assertEquals(List.of(Target.LOOP_PARALLEL, Target.KERNEL_API),
                Target.compared(java.util.EnumSet.of(Target.LOOP_PARALLEL, Target.KERNEL_API), true));
    }

    public void testTheStatusSaysHowLongTheModelTakesAndWhatItDoes() {
        List<String> shown = new ArrayList<>();
        Converter.AnswerStatus status = new Converter.AnswerStatus(2, shown::add, 0);
        status.show(5_000_000_000L);
        status.show(5_500_000_000L);
        status.streamed(false, 0);
        status.show(75_000_000_000L);
        status.streamed(true, 1234);
        assertEquals("Waiting for the model (answer 2 of 3), 5 s\u2026", shown.get(0));
        // Once a second at most, unless what it does changes
        assertEquals(shown.toString(), 4, shown.size());
        assertTrue(shown.get(1), shown.get(1).startsWith("The model is thinking (answer 2 of 3), "));
        assertEquals("The model is thinking (answer 2 of 3), 1 min 15 s\u2026", shown.get(2));
        assertTrue(shown.get(3), shown.get(3).startsWith("The model is writing (answer 2 of 3): 1,234 characters, "));
    }

    public void testTheConversionDialogsDescriptionsWrap() {
        String longest = MessageBundle.message("convert.target.fastest.description") + " "
                + MessageBundle.message("convert.target.fastest.cost", 9, 3, 3);
        java.awt.Dimension size = ConversionTargetDialog.description(longest).getPreferredSize();
        // Its width and the indent, on several lines
        assertTrue(size.toString(), size.width <= com.intellij.util.ui.JBUI.scale(ConversionTargetDialog.DESCRIPTION_WIDTH + 24 + 10));
        assertTrue(size.toString(), size.height > 3 * size.width / 100);
    }

    public void testKernelGrid() {
        KernelGrid grid = KernelGrid.parse("void f() {\n  // grid: Math.max(n, 1), n * 2\n  // local: 16, 16\n}");
        assertNotNull(grid);
        assertEquals(List.of("Math.max(n, 1)", "n * 2"), grid.global());
        assertEquals(List.of("16", "16"), grid.local());
        assertEquals("new WorkerGrid2D(Math.max(n, 1), n * 2)", grid.workerGrid());
        assertNull(KernelGrid.parse("void f() { }"));
    }

    public void testIsConvertible() {
        assertTrue(ConversionLauncher.isConvertible(methodAtCaret()));
        assertTrue(ConversionLauncher.hasTornadoApi(methodAtCaret()));
        myFixture.configureByText("K.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                public class K {
                    static void k<caret>(float[] a) { for (@Parallel int i = 0; i < a.length; i++) a[i] = 0; }
                }
                """);
        assertFalse("already a kernel", ConversionLauncher.isConvertible(methodAtCaret()));
    }

    private Converter.Result convert(LlmProvider model, boolean structured) throws LlmException, Converter.ConversionException {
        return convert(model, structured, null);
    }

    private Converter.Result convert(LlmProvider model, Converter.Verifier verifier)
            throws LlmException, Converter.ConversionException {
        return convert(model, verifier, Target.LOOP_PARALLEL);
    }

    private Converter.Result convert(LlmProvider model, Converter.Verifier verifier, Target target)
            throws LlmException, Converter.ConversionException {
        SmartPsiElementPointer<PsiMethod> pointer = SmartPointerManager.createPointer(methodAtCaret());
        return new Converter(model, false, AiSettingState.DEFAULT_LOCAL_MAX_TOKENS, null, verifier, target,
                new KernelVerifier.Device("CUDA", "NVIDIA GeForce RTX 5080 Laptop GPU"))
                .convert(pointer, () -> false, status -> { });
    }

    private Converter.Result convert(LlmProvider model, boolean structured, Integer contextLength)
            throws LlmException, Converter.ConversionException {
        SmartPsiElementPointer<PsiMethod> pointer = SmartPointerManager.createPointer(methodAtCaret());
        int maxTokens = contextLength == null ? AiSettingState.DEFAULT_LOCAL_MAX_TOKENS : 32_768;
        return new Converter(model, structured, maxTokens, contextLength, null, Target.LOOP_PARALLEL, null)
                .convert(pointer, () -> false, status -> { });
    }

    private String preview(Converter.Result result) {
        return KernelReplacement.preview(methodAtCaret(), result.kernelText(), KernelReplacement.Placement.REPLACE);
    }

    private void assertAcceptMatchesThePreview(Converter.Result result) {
        assertAcceptMatchesThePreview(result, KernelReplacement.Placement.REPLACE);
    }

    private void assertAcceptMatchesThePreview(Converter.Result result, KernelReplacement.Placement placement) {
        PsiMethod original = methodAtCaret();
        String preview = KernelReplacement.preview(original, result.kernelText(), placement);
        WriteCommandAction.writeCommandAction(getProject())
                .run(() -> KernelReplacement.place(original, result.kernelText(), placement));
        assertEquals(preview, myFixture.getFile().getText());
    }

    private PsiMethod methodAtCaret() {
        return PsiTreeUtil.getParentOfType(myFixture.getFile().findElementAt(myFixture.getCaretOffset()), PsiMethod.class);
    }

    /** Answers with the given texts in order, and records the requests. */
    private static final class ScriptedModel implements LlmProvider {
        /** Starts an answer that stopped at the token limit. */
        static final String CUT_OFF = "<cut off>";

        final Deque<String> answers;
        final List<LlmRequest> requests = new ArrayList<>();

        ScriptedModel(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        public @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled) {
            requests.add(request);
            String answer = answers.removeFirst();
            boolean cutOff = answer.startsWith(CUT_OFF);
            return new LlmResponse(cutOff ? answer.substring(CUT_OFF.length()) : answer, "scripted", cutOff, false);
        }

        @Override
        public @NotNull ModelInfo describe(@NotNull BooleanSupplier cancelled) {
            return new ModelInfo(List.of("scripted"), null);
        }
    }
}
