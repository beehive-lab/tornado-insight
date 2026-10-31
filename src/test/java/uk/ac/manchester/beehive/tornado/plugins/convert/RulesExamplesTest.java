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

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The examples in the rules files are what the model copies, so each must pass the checks its
 * answers go through: {@link CandidateChecker} and, with a context, a {@link KernelGrid}.
 */
public class RulesExamplesTest extends TornadoTestCase {

    private static final Pattern BLOCK = Pattern.compile("(?s)(\\S*)\\s*```java\\n(.*?)```");
    private static final Pattern CONTEXT = Pattern.compile("(KernelContext|TileContext) \\w+, ");

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
        addTileApi();
    }

    public void testLoopParallelExamplesPass() throws IOException {
        assertExamplesPass(Target.LOOP_PARALLEL, 5);
    }

    public void testKernelApiExamplesPass() throws IOException {
        assertExamplesPass(Target.KERNEL_API, 3);
    }

    public void testTileApiExamplesPass() throws IOException {
        assertExamplesPass(Target.TILE_API, 2);
    }

    private void assertExamplesPass(Target target, int count) throws IOException {
        List<String> examples = examples(target.rules());
        assertEquals(examples.toString(), count, examples.size());
        for (String example : examples) {
            PsiMethod original = original(example);
            PsiFile copy = (PsiFile) original.getContainingFile().copy();
            KernelReplacement.Replaced replaced = KernelReplacement.replace(PsiTreeUtil.findSameElementInCopy(original, copy), example);
            assertNotNull(example, replaced);
            assertEquals(example, List.of(), CandidateChecker.problems(original, replaced.kernel(), target));
            if (target.context() != null) {
                assertNotNull(example, KernelGrid.parse(example));
            }
        }
    }

    // The fenced Java blocks, except those after "Wrong:"
    private static List<String> examples(String resource) throws IOException {
        String text;
        try (InputStream in = RulesExamplesTest.class.getResourceAsStream(resource)) {
            assertNotNull(resource, in);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> examples = new ArrayList<>();
        Matcher block = BLOCK.matcher(text);
        while (block.find()) {
            if (!block.group(1).equals("Wrong:")) {
                examples.add(block.group(2));
            }
        }
        return examples;
    }

    // A plain method with the example's parameters, without its context and annotations
    private PsiMethod original(String example) {
        String signature = example.substring(0, example.indexOf('{'))
                .replace("@Reduce ", "")
                .replaceAll("@Parallel\\s*", "");
        myFixture.configureByText("Example.java", """
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
                public class Example {
                    %s{ }
                }
                """.formatted(CONTEXT.matcher(signature).replaceFirst("")));
        return PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiMethod.class);
    }

    // The part of the Tile API the examples use, with TornadoVM 7.0.0's signatures
    private void addTileApi() {
        myFixture.addClass("package uk.ac.manchester.tornado.api.tile; public class Tile { }");
        myFixture.addClass("package uk.ac.manchester.tornado.api.tile; public class TensorView { }");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.tile;
                public class PartitionView {
                    public Tile loadMasked(int blockX) { return null; }
                    public void storeMasked(Tile tile, int blockX) { }
                    public void atomicAdd(Tile tile, int blockX) { }
                }""");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.tile;
                import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
                public class TileContext {
                    public int bidX() { return 0; }
                    public TensorView view(FloatArray array, int extent) { return null; }
                    public PartitionView partition(TensorView view, int tileExtent) { return null; }
                    public Tile add(Tile a, Tile b) { return null; }
                    public Tile scale(Tile a, double scalar) { return null; }
                    public Tile sum(Tile a, int axis) { return null; }
                    public Tile reshape(Tile a, int extent) { return null; }
                }""");
    }
}
