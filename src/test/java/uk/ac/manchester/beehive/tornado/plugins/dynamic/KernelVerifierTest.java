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

import org.junit.Test;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier.Outcome;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier.Status;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationHarnessGenerator;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationModel;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.TornadoToolchain;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KernelVerifierTest {

    private static final String WARNING = "WARNING: Using incubator modules: jdk.incubator.vector\n";

    // The method under test does not know the tag, so what it prints is not a finding
    @Test
    public void onlyLinesWithTheRunsTagAreFindings() {
        Outcome outcome = KernelVerifier.outcome("""
                INSIGHT t data 1
                INSIGHT shape 1 4096
                INSIGHT equal
                INSIGHT t shape 1 4096
                INSIGHT t mismatch y: 1 of 4096 values differ
                INSIGHT t different
                """, WARNING, 0, "t");
        assertEquals(Status.DIFFERENT, outcome.status());
        Outcome spoofed = KernelVerifier.outcome("INSIGHT data 1\nINSIGHT shape 1 4096\nINSIGHT equal\n", WARNING, 0, "t");
        assertEquals(Status.KERNEL_FAILED, spoofed.status());
    }

    @Test
    public void unreadableFindingsAreNotAVerdict() {
        Outcome outcome = KernelVerifier.outcome("INSIGHT t data 1\nINSIGHT t shape x 4096\n", WARNING, 0, "t");
        assertEquals(Status.NOT_VERIFIED, outcome.status());
        assertTrue(outcome.details().get(0), outcome.details().get(0).startsWith("The test program's output could not be read"));
    }

    // The harness runs the original method first: when it hangs, the kernel is not to blame
    @Test
    public void aTimeoutIsTheKernelsOnlyIfTheOriginalMethodWasNotRunning() {
        assertEquals(Status.NOT_VERIFIED, KernelVerifier.timedOut("INSIGHT t data 1\n", "t", false, "5 minutes").status());
        assertEquals(Status.NOT_VERIFIED, KernelVerifier.timedOut(
                "INSIGHT t data 1\nINSIGHT t shape 1 4096\nINSIGHT t equal\nINSIGHT t data 2\n", "t", false, "5 minutes").status());
        Outcome kernel = KernelVerifier.timedOut("INSIGHT t data 1\nINSIGHT t shape 1 4096\n", "t", false, "5 minutes");
        assertEquals(Status.KERNEL_FAILED, kernel.status());
        assertEquals("The kernel did not finish within 5 minutes.", kernel.details().get(0));
        // Timing: the original method is timed after the check, then the kernel
        String verified = "INSIGHT t data 1\nINSIGHT t shape 1 4096\nINSIGHT t equal\n";
        assertEquals(Status.NOT_VERIFIED, KernelVerifier.timedOut(verified, "t", true, "5 minutes").status());
        assertEquals(Status.KERNEL_FAILED, KernelVerifier.timedOut(verified + "INSIGHT t timing-kernel\n", "t", true, "5 minutes").status());
    }

    // Asked before the model is, so that Fastest and the run option are not offered for nothing
    @Test
    public void aMethodIsTestableIfTheHarnessCanFillItsParametersAndResult() {
        List<VerificationModel.Parameter> saxpy = List.of(new VerificationModel.Parameter("a", "float"),
                new VerificationModel.Parameter("x", "float[]"),
                new VerificationModel.Parameter("y", "uk.ac.manchester.tornado.api.types.arrays.FloatArray"));
        assertNull(VerificationHarnessGenerator.unsupported(saxpy, "void"));
        assertNull(VerificationHarnessGenerator.unsupported(saxpy, "float"));
        assertEquals("unsupported parameter type float[][] (parameter 'm')", VerificationHarnessGenerator.unsupported(
                List.of(new VerificationModel.Parameter("m", "float[][]")), "void"));
        assertEquals("unsupported parameter type java.lang.String (parameter 'the returned value')",
                VerificationHarnessGenerator.unsupported(saxpy, "java.lang.String"));
    }

    @Test
    public void theDefaultDeviceAndWhetherItRunsTiles() {
        KernelVerifier.Device cuda = KernelVerifier.defaultDevice("""
                Number of Tornado drivers: 1
                Driver: \u001B[32mCUDADriver\u001B[0m
                  Total number of CUDADriver devices  : 1
                  Tornado device=0:0  (DEFAULT)
                \t\u001B[32mCUDA\u001B[0m --  [NVIDIA CUDA] -- NVIDIA GeForce RTX 5080 Laptop GPU
                \t\tGlobal Memory Size: 15.5 GB
                """);
        assertNotNull(cuda);
        assertEquals("CUDA", cuda.backend());
        assertEquals("NVIDIA GeForce RTX 5080 Laptop GPU", cuda.name());
        assertTrue(cuda.runsTiles());
        // OpenCL on an NVIDIA GPU names the CUDA platform, but it is not the CUDA backend
        KernelVerifier.Device openCl = KernelVerifier.defaultDevice("""
                  Tornado device=0:0  (DEFAULT)
                \tOPENCL --  [NVIDIA CUDA] -- NVIDIA GeForce RTX 5080 Laptop GPU
                """);
        assertNotNull(openCl);
        assertFalse(openCl.runsTiles());
        assertNull(KernelVerifier.defaultDevice("no devices"));
    }

    @Test
    public void equalOutputsOnBothDataSets() {
        Outcome outcome = KernelVerifier.outcome("""
                INSIGHT t data 1
                INSIGHT t shape 1 4096
                INSIGHT t equal
                INSIGHT t data 2
                INSIGHT t shape 1 4097
                INSIGHT t equal
                """, WARNING, 0, "t");
        assertEquals(Status.EQUAL, outcome.status());
        assertEquals("4096 elements of values from 0.5 to 1.5, and 4097 elements of values from -1 to 1 with zeros",
                outcome.shape());
        assertNull(outcome.timing());
    }

    @Test
    public void equalOutputsOnOneDataSetAreNotEnough() {
        Outcome outcome = KernelVerifier.outcome("""
                INSIGHT t data 1
                INSIGHT t shape 1 4096
                INSIGHT t equal
                INSIGHT t data 2
                INSIGHT t shape 1 4097
                """, WARNING, 0, "t");
        assertEquals(Status.KERNEL_FAILED, outcome.status());
    }

    @Test
    public void aSecondDataSetTheMethodFailsOnIsSaidToBeSkipped() {
        Outcome outcome = KernelVerifier.outcome("""
                INSIGHT t data 1
                INSIGHT t shape 1 4096
                INSIGHT t equal
                INSIGHT t data 2
                INSIGHT t data-skipped java.lang.ArrayIndexOutOfBoundsException: Index -5 out of bounds
                """, WARNING, 0, "t");
        assertEquals(Status.EQUAL, outcome.status());
        assertTrue(outcome.shape(), outcome.shape().startsWith("4096 elements of values from 0.5 to 1.5 (not values from -1 to 1"));
        assertTrue(outcome.shape(), outcome.shape().contains("Index -5 out of bounds"));
    }

    @Test
    public void aMismatchOnTheSecondDataSetSaysSo() {
        Outcome outcome = KernelVerifier.outcome("""
                INSIGHT t data 1
                INSIGHT t shape 1 4096
                INSIGHT t equal
                INSIGHT t data 2
                INSIGHT t shape 1 4097
                INSIGHT t mismatch y: 1 of 4097 values differ; at index 4096 the method gives -0.2 and the kernel 0.0
                INSIGHT t different
                """, WARNING, 0, "t");
        assertEquals(Status.DIFFERENT, outcome.status());
        assertTrue(outcome.details().get(0), outcome.details().get(0).startsWith("On the second data set (an odd length"));
    }

    @Test
    public void aKernelTheProfilerSawNoTimeOnTheDeviceForIsAFailure() {
        Outcome outcome = KernelVerifier.outcome("INSIGHT t data 1\nINSIGHT t shape 1 4096\nINSIGHT t not-on-device\n", WARNING, 0, "t");
        assertEquals(Status.KERNEL_FAILED, outcome.status());
        assertTrue(outcome.details().get(0), outcome.details().get(0).contains("in Java instead of on the device"));
    }

    @Test
    public void timesAreRead() {
        Outcome outcome = KernelVerifier.outcome(
                "INSIGHT t data 1\nINSIGHT t shape 2 64\nINSIGHT t equal\nINSIGHT t time 864333 10147704 282848 3865760\n", WARNING, 0, "t");
        assertEquals(Status.EQUAL, outcome.status());
        assertEquals("4096 elements (64²) of values from 0.5 to 1.5", outcome.shape());
        assertNotNull(outcome.timing());
        assertEquals(864333, outcome.timing().java());
        assertEquals(10147704, outcome.timing().kernel());
        assertEquals(282848, outcome.timing().deviceKernel());
        assertEquals(3865760, outcome.timing().transfers());
    }

    @Test
    public void mismatchesAreTheModelsToFix() {
        Outcome outcome = KernelVerifier.outcome("""
                INSIGHT t data 1
                INSIGHT t shape 1 4096
                INSIGHT t mismatch y: 4096 of 4096 values differ; at index 0 the method gives 1.9 and the kernel 1.07
                INSIGHT t different
                """, WARNING, 0, "t");
        assertEquals(Status.DIFFERENT, outcome.status());
        assertEquals(1, outcome.details().size());
        assertTrue(outcome.details().get(0), outcome.details().get(0).contains("y: 4096 of 4096 values differ"));
    }

    @Test
    public void aKernelThatTornadoCannotRun() {
        Outcome outcome = KernelVerifier.outcome("INSIGHT t shape 1 4096\nINSIGHT t kernel-failed uk.ac.manchester.tornado.api.exceptions."
                + "TornadoBailoutRuntimeException: Unable to compile task\n", WARNING, 0, "t");
        assertEquals(Status.KERNEL_FAILED, outcome.status());
        assertTrue(outcome.details().get(0), outcome.details().get(0).contains("Unable to compile task"));
    }

    @Test
    public void aBailoutIsAFailureEvenIfTheOutputsMatch() {
        Outcome outcome = KernelVerifier.outcome("INSIGHT t data 1\nINSIGHT t shape 1 4096\nINSIGHT t equal\n",
                WARNING + "[Bailout] Running the sequential implementation. Enable --debug to see the reason.\n", 0, "t");
        assertEquals(Status.KERNEL_FAILED, outcome.status());
    }

    @Test
    public void anOriginalThatFailsCannotBeVerified() {
        Outcome outcome = KernelVerifier.outcome("INSIGHT t original-failed java.lang.ArrayIndexOutOfBoundsException: Index 9 out of bounds\n",
                WARNING, 0, "t");
        assertEquals(Status.NOT_VERIFIED, outcome.status());
    }

    @Test
    public void aCrashWithoutFindingsIsAKernelFailure() {
        Outcome outcome = KernelVerifier.outcome("INSIGHT t shape 1 4096\n", WARNING + "# A fatal error has been detected by the Java Runtime\n", 134, "t");
        assertEquals(Status.KERNEL_FAILED, outcome.status());
        assertTrue(outcome.details().get(0), outcome.details().get(0).contains("exit code 134"));
    }

    @Test
    public void theModelIsNotToldTheMachinesDirectories() {
        TornadoToolchain toolchain = new TornadoToolchain(TornadoSdk.forHome("/home/ana/sdks/tornadovm-7.3.1", false),
                "/home/ana/.jdks/temurin-25", 25);
        String output = "Exception in /tmp/tornado-insight-verify123/run: cannot load "
                + "/home/ana/sdks/tornadovm-7.3.1/lib/libtornado-ptx.so with /home/ana/.jdks/temurin-25/bin/java "
                + "for /home/ana/project/Saxpy.java";
        assertEquals("Exception in <work>/run: cannot load $TORNADOVM_HOME/lib/libtornado-ptx.so with "
                        + "$JAVA_HOME/bin/java for ~/project/Saxpy.java",
                KernelVerifier.withoutPaths(output, toolchain, Path.of("/tmp/tornado-insight-verify123"), "/home/ana"));
    }
}
