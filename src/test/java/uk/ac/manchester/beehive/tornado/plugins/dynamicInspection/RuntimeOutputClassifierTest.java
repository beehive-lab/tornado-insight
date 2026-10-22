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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RuntimeOutputClassifierTest {

    // Real 'tornado --printKernel -jar' output (TornadoVM 7.3.1, CUDA backend)
    private static final String CUDA_KERNEL = CapturedOutputs.load("cuda-vadd.stdout");
    private static final String JVM_WARNINGS = CapturedOutputs.load("cuda-vadd.stderr");
    private static final String GRAAL_ERROR = CapturedOutputs.load("cuda-graal-error.stderr");

    @Test
    public void cleanRunWithKernelAndJvmWarningsIsNoError() {
        assertFalse(RuntimeOutputClassifier.detectRuntimeError(CUDA_KERNEL, JVM_WARNINGS));
        assertEquals(CUDA_KERNEL.strip(), RuntimeOutputClassifier.stripErrorLines(CUDA_KERNEL));
    }

    @Test
    public void uncaughtCompilerErrorIsAnError() {
        assertTrue(RuntimeOutputClassifier.detectRuntimeError("", GRAAL_ERROR));
        assertNull(RuntimeOutputClassifier.describeDeviceLimitation("", GRAAL_ERROR));
        String errors = RuntimeOutputClassifier.extractErrorLines("", GRAAL_ERROR);
        assertTrue(errors, errors.startsWith("Exception in thread \"main\" tornado.graal.compiler.debug.GraalError"));
    }

    @Test
    public void jniErrorLinesAreStrippedFromTheKernel() {
        String stdout = "__kernel void k(__global long *_kernel_context)\n{\n}  //  kernel\n"
                + "[JNI] uk.ac.manchester.tornado.drivers.opencl> notify error:\n"
                + "[TornadoVM-OCL-JNI] ERROR : clEnqueueNDRangeKernel -> Returned: CL_OUT_OF_RESOURCES\n";
        assertTrue(RuntimeOutputClassifier.detectRuntimeError(stdout, ""));
        assertEquals("__kernel void k(__global long *_kernel_context)\n{\n}  //  kernel",
                RuntimeOutputClassifier.stripErrorLines(stdout));
        assertEquals("[TornadoVM-OCL-JNI] ERROR : clEnqueueNDRangeKernel -> Returned: CL_OUT_OF_RESOURCES",
                RuntimeOutputClassifier.extractErrorLines(stdout, ""));
    }

    @Test
    public void fp16LimitationIsRecognised() {
        String stderr = "Exception in thread \"main\" uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP16NotSupported:"
                + " The current OpenCL device (Intel(R) UHD Graphics) does not support FP16\n";
        assertTrue(RuntimeOutputClassifier.detectRuntimeError("", stderr));
        assertNotNull(RuntimeOutputClassifier.describeDeviceLimitation("", stderr));
    }
}
