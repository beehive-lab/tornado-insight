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

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels.Kind;

import java.util.List;

public class TornadoKernelsTest extends TornadoTestCase {

    public void testKindsAndDocumentOrder() {
        addTornadoApi();
        addTileContext();
        PsiFile file = myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.KernelContext;
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                import uk.ac.manchester.tornado.api.annotations.Reduce;
                import uk.ac.manchester.tornado.api.tile.TileContext;

                class Demo {
                    static void tile(TileContext tc, int[] a) { }
                    static void parallel(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; } }
                    static void helper(int[] a) { }
                    static void reduce(int[] a, @Reduce int[] sum) { }
                    static void kernel(KernelContext context, int[] a) { }
                }""");

        List<PsiMethod> kernels = TornadoKernels.findKernels(file);

        assertEquals(List.of("tile", "parallel", "reduce", "kernel"), kernels.stream().map(PsiMethod::getName).toList());
        assertEquals(Kind.TILE_CONTEXT, TornadoKernels.kind(kernels.get(0)));
        assertEquals(Kind.PARALLEL_LOOP, TornadoKernels.kind(kernels.get(1)));
        assertEquals(Kind.PARALLEL_LOOP, TornadoKernels.kind(kernels.get(2)));
        assertEquals(Kind.KERNEL_CONTEXT, TornadoKernels.kind(kernels.get(3)));
        assertNull(TornadoKernels.kind(method(file, "helper")));
    }

    public void testUnresolvedTornadoTypesFallBackToSimpleNames() {
        // No TornadoVM API stubs: the annotation and the context type do not resolve
        PsiFile file = myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.KernelContext;
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Demo {
                    static void parallel(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; } }
                    static void kernel(KernelContext context, int[] a) { }
                }""");

        assertEquals(List.of("parallel", "kernel"), TornadoKernels.findKernels(file).stream().map(PsiMethod::getName).toList());
    }

    public void testResolvedLookalikesAreNotKernels() {
        addTornadoApi();
        myFixture.addClass("package other; public @interface Parallel {}");
        myFixture.addClass("package other; public class MyKernelContext {}");
        PsiFile file = myFixture.configureByText("Demo.java", """
                import other.MyKernelContext;
                import other.Parallel;

                class Demo {
                    static void parallel(int[] a) { for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; } }
                    static void kernel(MyKernelContext context, int[] a) { }
                }""");

        assertEmpty(TornadoKernels.findKernels(file));
    }

    public void testAnnotationInNestedMethodBelongsToThatMethod() {
        addTornadoApi();
        PsiFile file = myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Demo {
                    static void outer(int[] a) {
                        Runnable r = new Runnable() {
                            public void run() { for (@Parallel int i = 0; i < a.length; i++) { a[i] = i; } }
                        };
                    }
                }""");

        assertEquals(List.of("run"), TornadoKernels.findKernels(file).stream().map(PsiMethod::getName).toList());
    }

    private static PsiMethod method(PsiFile file, String name) {
        return PsiTreeUtil.findChildrenOfType(file, PsiMethod.class).stream()
                .filter(m -> name.equals(m.getName())).findFirst().orElseThrow();
    }
}
