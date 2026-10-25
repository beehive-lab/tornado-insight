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

package uk.ac.manchester.beehive.tornado.plugins.dynamic;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DebugDumpTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void defaultsToTheSystemTemporaryFolder() {
        Path expected = Path.of(System.getProperty("java.io.tmpdir"), DebugDump.FOLDER);
        assertEquals(expected, DebugDump.inTemporaryFolder().directory());
    }

    @Test
    public void writesTheTestAndTheKernelOfATask() throws Exception {
        DebugDump dump = new DebugDump(temp.getRoot().toPath().resolve("dump"));
        dump.clear();

        Path test = dump.writeTest("VectorAddTest", "class VectorAddTest {}");
        Path kernel = dump.writeKernel("VectorAddTest", "__kernel void add(__global int *a) {}");

        assertEquals("VectorAddTest.java", test.getFileName().toString());
        assertEquals("class VectorAddTest {}", Files.readString(test));
        assertEquals("VectorAddTest.kernel.cl", kernel.getFileName().toString());
        assertEquals("__kernel void add(__global int *a) {}", Files.readString(kernel));
    }

    @Test
    public void clearRemovesThePreviousRun() throws Exception {
        Path directory = temp.getRoot().toPath().resolve("dump");
        DebugDump dump = new DebugDump(directory);
        dump.clear();
        dump.writeTest("OldTest", "class OldTest {}");
        Files.createDirectories(directory.resolve("nested")).resolve("leftover.txt").toFile().createNewFile();

        dump.clear();

        assertTrue(Files.isDirectory(directory));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    public void clearCreatesAMissingFolder() throws Exception {
        Path directory = temp.getRoot().toPath().resolve("a").resolve("b");
        assertFalse(Files.exists(directory));
        new DebugDump(directory).clear();
        assertTrue(Files.isDirectory(directory));
    }

    @Test
    public void kernelExtensionFollowsTheBackend() {
        assertEquals("cl", DebugDump.extension("#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n__kernel void add(__global long *_kernel_context) {}"));
        assertEquals("cu", DebugDump.extension("extern \"C\" __global__ void add(long long *_kernel_context) {}"));
        assertEquals("ptx", DebugDump.extension(".version 7.6\n.target sm_86\n.address_size 64\n.entry add() {}"));
        assertEquals("txt", DebugDump.extension("something else"));
    }
}
