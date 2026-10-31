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

import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;

import java.util.List;

// A platform test only for the message bundle
public class ComparisonTest extends TornadoTestCase {

    private static Comparison.Entry timed(Target target, long kernel) {
        KernelVerifier.Outcome verified = new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "4096 elements", null);
        Converter.Result result = new Converter.Result("void k() { }", "k", "void k() { }", List.of(), 1, false, "m",
                false, "", 0, verified, target, null);
        KernelVerifier.Outcome timing = new KernelVerifier.Outcome(KernelVerifier.Status.EQUAL, List.of(), "1048576 elements",
                new KernelVerifier.Timing(1_000_000, kernel, kernel / 2, kernel / 4));
        return new Comparison.Entry(target, result, timing, null);
    }

    public void testTheReadableKernelIsKeptUnlessTheOtherIsClearlyFaster() {
        // 10% faster: not enough at a 20% threshold
        assertEquals(0, Comparison.choose(List.of(timed(Target.LOOP_PARALLEL, 1_000_000),
                timed(Target.KERNEL_API, 910_000)), 20));
        // 2x faster
        assertEquals(1, Comparison.choose(List.of(timed(Target.LOOP_PARALLEL, 1_000_000),
                timed(Target.KERNEL_API, 500_000)), 20));
        // Slower
        assertEquals(0, Comparison.choose(List.of(timed(Target.LOOP_PARALLEL, 1_000_000),
                timed(Target.KERNEL_API, 2_000_000)), 0));
    }

    public void testAKernelThatWasNotTimedIsNotRecommended() {
        Comparison.Entry failed = new Comparison.Entry(Target.LOOP_PARALLEL, null, null, "no code");
        assertEquals(1, Comparison.choose(List.of(failed, timed(Target.KERNEL_API, 5_000_000)), 20));
    }

    public void testEveryFailureIsListedOnce() {
        Comparison comparison = Comparison.of(List.of(
                new Comparison.Entry(Target.LOOP_PARALLEL, null, null, "server down"),
                new Comparison.Entry(Target.KERNEL_API, null, null, "no code"),
                new Comparison.Entry(Target.TILE_API, null, null, "server down")), 1 << 20, 10, 20);
        assertEquals(List.of("Loop Parallel API, Tile API: server down", "Kernel API: no code"), comparison.failures());
    }

    public void testTable() {
        Comparison comparison = Comparison.of(List.of(timed(Target.LOOP_PARALLEL, 10_000_000),
                timed(Target.KERNEL_API, 250_000)), 1 << 20, 10, 20);
        assertEquals(1, comparison.chosen());
        String table = comparison.table();
        assertTrue(table, table.contains("Java (the method)"));
        assertTrue(table, table.contains("10.0 ms"));
        assertTrue(table, table.contains("0.250 ms"));
        assertTrue(table, table.contains("10.0× slower"));
        assertTrue(table, table.contains("4.0× faster"));
        assertTrue(table, table.contains("<b>Kernel API</b>"));
    }
}
