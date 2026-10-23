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

package uk.ac.manchester.beehive.tornado.plugins;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/**
 * Base class for fixture tests. Stubs the parts of the TornadoVM API the plugin resolves,
 * so tests run without a TornadoVM SDK on the machine.
 */
public abstract class TornadoTestCase extends LightJavaCodeInsightFixtureTestCase {

    protected void addTornadoApi() {
        myFixture.addClass("package uk.ac.manchester.tornado.api; public class TaskGraph {}");
        myFixture.addClass("package uk.ac.manchester.tornado.api; public class TornadoExecutionPlan {}");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api;
                public class KernelContext {
                    public int globalIdx, globalIdy, globalIdz, localIdx, localGroupSizeX;
                    public int[] allocateIntLocalArray(int size) { return new int[size]; }
                    public float[] allocateFloatLocalArray(int size) { return new float[size]; }
                    public void localBarrier() { }
                }""");
        myFixture.addClass("package uk.ac.manchester.tornado.api.enums; public enum DataTransferMode { FIRST_EXECUTION, EVERY_EXECUTION }");
        myFixture.addClass("package uk.ac.manchester.tornado.api.annotations; public @interface Parallel {}");
        myFixture.addClass("package uk.ac.manchester.tornado.api.annotations; public @interface Reduce {}");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.types.arrays;
                public class IntArray {
                    public int get(int index) { return 0; }
                    public void set(int index, int value) { }
                    public int getSize() { return 0; }
                }""");
        myFixture.addClass("""
                package uk.ac.manchester.tornado.api.types.arrays;
                public class FloatArray {
                    public float get(int index) { return 0; }
                    public void set(int index, float value) { }
                    public int getSize() { return 0; }
                }""");
    }

    /**
     * The light test project has no JDK classes, so stub the ones the kernel
     * inspections recognise as system calls, and String, which the tests use as an
     * unsupported kernel data type (unresolved types are not classified).
     */
    protected void addSystemStubs() {
        myFixture.addClass("package java.lang; public final class String { }");
        myFixture.addClass("package java.io; public class PrintStream { public void println(int x) { } }");
        myFixture.addClass("package java.lang; public final class System { public static final java.io.PrintStream out = null; }");
    }

    protected void addTileContext() {
        myFixture.addClass("package uk.ac.manchester.tornado.api.tile; public class TileContext {}");
    }
}
