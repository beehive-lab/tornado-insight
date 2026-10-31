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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;

public class ParallelPlacementTest extends TornadoTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addTornadoApi();
    }

    public void testLoopIndexIsTheOnlyPlace() {
        PsiMethod method = method("""
                @Parallel
                static void k(@Parallel float[] a, int n) {
                    @Parallel int local = 0;
                    for (@Parallel int i = 0; i < n; i++) {
                        a[i] = local;
                    }
                }""");
        assertEquals(3, ParallelPlacement.misplaced(method).size());
    }

    public void testCorrectKernelHasNoProblem() {
        PsiMethod method = method("""
                static void k(float[] a) {
                    for (@Parallel int i = 0; i < a.length; i++) {
                        for (@Parallel int j = 0; j < a.length; j++) {
                            a[i] = j;
                        }
                    }
                }""");
        assertEmpty(ParallelPlacement.misplaced(method));
        assertFalse(ParallelPlacement.repair(method));
    }

    public void testRepairMovesItToTheOnlyOuterLoop() {
        PsiMethod method = method("""
                @Parallel
                static void k(float[] a) {
                    for (int i = 0; i < a.length; i++) {
                        for (int j = 0; j < 4; j++) {
                            a[i] += j;
                        }
                    }
                }""");
        assertTrue(WriteCommandAction.writeCommandAction(getProject()).compute(() -> ParallelPlacement.repair(method)));
        assertEmpty(ParallelPlacement.misplaced(method));
        assertTrue(method.getText(), method.getText().contains("for (@Parallel int i = 0;"));
        assertTrue(method.getText(), method.getText().contains("for (int j = 0;"));
    }

    public void testRepairOnlyRemovesWhenTheLoopIsAmbiguous() {
        PsiMethod method = method("""
                @Parallel
                static void k(float[] a, float[] b) {
                    for (int i = 0; i < a.length; i++) a[i] = 0;
                    for (int i = 0; i < b.length; i++) b[i] = 0;
                }""");
        assertTrue(WriteCommandAction.writeCommandAction(getProject()).compute(() -> ParallelPlacement.repair(method)));
        assertFalse(method.getText(), method.getText().contains("@Parallel"));
    }

    public void testRepairKeepsTheCorrectOnes() {
        PsiMethod method = method("""
                static void k(@Parallel float[] a) {
                    for (@Parallel int i = 0; i < a.length; i++) a[i] = 0;
                }""");
        assertTrue(WriteCommandAction.writeCommandAction(getProject()).compute(() -> ParallelPlacement.repair(method)));
        assertEquals(1, PsiTreeUtil.findChildrenOfType(method, PsiAnnotation.class).size());
        assertTrue(method.getText(), method.getText().contains("for (@Parallel int i"));
    }

    private PsiMethod method(String code) {
        myFixture.configureByText("K.java", "import uk.ac.manchester.tornado.api.annotations.Parallel;\nclass K {\n" + code + "\n}");
        return PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiMethod.class);
    }
}
