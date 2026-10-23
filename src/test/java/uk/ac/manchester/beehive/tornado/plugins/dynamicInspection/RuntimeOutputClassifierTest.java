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
import uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.RuntimeOutputClassifier.Verdict;

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

    // Representative --printKernel output of the OpenCL backend for a device that
    // supports FP64: OCLAssembler starts every kernel with these pragmas.
    private static final String OPENCL_KERNEL = """
            #pragma OPENCL EXTENSION cl_khr_fp64 : enable \s
            #pragma OPENCL EXTENSION cl_khr_fp16 : enable \s
            #pragma OPENCL EXTENSION cl_khr_int64_base_atomics : enable \s
            __kernel void vadd(__global long *_kernel_context, __constant uchar *_constant_region, __local uchar *_local_region, __global int *_atomics, __global uchar *a, __global uchar *b, __global uchar *c)
            {
              ulong ul_0, ul_1, ul_2, ul_9, ul_11, ul_13;\s
              double d_10, d_12, d_14;\s

              // BLOCK 0
              ul_0  =  (ulong) a;
              d_14  =  d_10 + d_12;
              *((__global double *) ul_13)  =  d_14;
            }  //  kernel
            """;

    private static final String OPENCL_BUILD_ERROR = """

            [ERROR] TornadoVM JIT Compiler - OpenCL Build Error Log:

            <kernel>:12:3: error: use of undeclared identifier 'd_10'
              d_14  =  d_10 + d_12;
              ^

            Exception in thread "main" uk.ac.manchester.tornado.api.exceptions.TornadoBailoutRuntimeException: Error during code compilation with the OpenCL driver
            \tat tornado.drivers.opencl@7.3.1/uk.ac.manchester.tornado.drivers.opencl.OCLCodeCache.installSource(OCLCodeCache.java:186)
            \tat VaddAbc12.main(VaddAbc12.java:25)
            """;

    @Test
    public void cleanRunWithKernelAndJvmWarningsIsNoError() {
        Verdict verdict = RuntimeOutputClassifier.classify(CUDA_KERNEL, JVM_WARNINGS, 0);
        assertFalse(verdict.failed());
        assertEquals(CUDA_KERNEL.strip(), verdict.kernelSource());
    }

    @Test
    public void uncaughtCompilerErrorIsAnError() {
        Verdict verdict = RuntimeOutputClassifier.classify("", GRAAL_ERROR, 1);
        assertTrue(verdict.failed());
        assertNull(verdict.deviceLimitation());
        assertTrue(verdict.errorDetail(), verdict.errorDetail().startsWith(
                "Exception in thread \"main\" tornado.graal.compiler.debug.GraalError: should not reach here"));
        // Only the exception line, not the stack trace or the JVM warnings
        assertEquals(1, verdict.errorDetail().lines().count());
    }

    @Test
    public void fp64PragmaInThePrintedKernelIsNotADeviceLimitation() {
        Verdict verdict = RuntimeOutputClassifier.classify(OPENCL_KERNEL, OPENCL_BUILD_ERROR, 1);
        assertTrue(verdict.failed());
        assertNull(verdict.deviceLimitation());
        assertTrue(verdict.errorDetail(), verdict.errorDetail().contains("TornadoBailoutRuntimeException"));
        assertEquals(OPENCL_KERNEL.strip(), verdict.kernelSource());
    }

    @Test
    public void jniErrorAfterAnFp64KernelIsNotADeviceLimitation() {
        String stdout = OPENCL_KERNEL
                + "[JNI] uk.ac.manchester.tornado.drivers.opencl> notify error:\n"
                + "[TornadoVM-OCL-JNI] ERROR : clEnqueueNDRangeKernel -> Returned: CL_OUT_OF_RESOURCES\n";
        Verdict verdict = RuntimeOutputClassifier.classify(stdout, JVM_WARNINGS, 0);
        assertTrue(verdict.failed());
        assertNull(verdict.deviceLimitation());
        assertEquals("[TornadoVM-OCL-JNI] ERROR : clEnqueueNDRangeKernel -> Returned: CL_OUT_OF_RESOURCES",
                verdict.errorDetail());
        assertEquals(OPENCL_KERNEL.strip(), verdict.kernelSource());
    }

    @Test
    public void exceptionInAKernelOrVariableNameIsNoError() {
        String stdout = CUDA_KERNEL.replace("void vadd(", "void checkNoException(")
                .replace("// BLOCK 0", "// BLOCK 0 Exception: none");
        assertFalse(RuntimeOutputClassifier.classify(stdout, JVM_WARNINGS, 0).failed());
        assertFalse(RuntimeOutputClassifier.detectRuntimeError(stdout, JVM_WARNINGS));
    }

    @Test
    public void fp64LimitationIsRecognised() {
        String stderr = "Exception in thread \"main\" uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP64NotSupported:"
                + " java.util.concurrent.ExecutionException: The current OpenCL device (Intel(R) UHD Graphics 620) does not support FP64\n"
                + "\tat tornado.runtime@7.3.1/uk.ac.manchester.tornado.runtime.TornadoVM.withPreCompilation(TornadoVM.java:167)\n";
        Verdict verdict = RuntimeOutputClassifier.classify("", stderr, 1);
        assertTrue(verdict.failed());
        assertNotNull(verdict.deviceLimitation());
        assertTrue(verdict.deviceLimitation().contains("FP64"));
    }

    @Test
    public void fp16LimitationIsRecognised() {
        String stderr = "Exception in thread \"main\" uk.ac.manchester.tornado.api.exceptions.TornadoDeviceFP16NotSupported:"
                + " The current OpenCL device (Intel(R) UHD Graphics) does not support FP16\n";
        Verdict verdict = RuntimeOutputClassifier.classify(OPENCL_KERNEL, stderr, 1);
        assertTrue(verdict.failed());
        assertTrue(verdict.deviceLimitation(), verdict.deviceLimitation().contains("FP16"));
    }

    @Test
    public void floatAtomicsLimitationIsRecognised() {
        String stderr = "Exception in thread \"main\" tornado.graal.compiler.debug.GraalError: unimplemented:"
                + " In OpenCL, the atom_add function does not support floating point operations.\n";
        Verdict verdict = RuntimeOutputClassifier.classify("", stderr, 1);
        assertTrue(verdict.deviceLimitation(), verdict.deviceLimitation().contains("floating-point atomic"));
    }

    @Test
    public void nonZeroExitWithoutDiagnosticsIsAnError() {
        String stdout = "#\n# A fatal error has been detected by the Java Runtime Environment:\n#\n#  SIGSEGV (0xb) at pc=0x00007f3c2a4b1c20\n";
        Verdict verdict = RuntimeOutputClassifier.classify(stdout, JVM_WARNINGS, 134);
        assertTrue(verdict.failed());
        assertTrue(verdict.errorDetail(), verdict.errorDetail().startsWith("tornado exited with code 134:\nWARNING:"));
    }

    @Test
    public void errorLines() {
        assertTrue(RuntimeOutputClassifier.isErrorLine("Exception in thread \"main\" java.lang.IllegalStateException: x"));
        assertTrue(RuntimeOutputClassifier.isErrorLine("Caused by: java.lang.NullPointerException"));
        assertTrue(RuntimeOutputClassifier.isErrorLine("uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException: boom"));
        assertTrue(RuntimeOutputClassifier.isErrorLine("[TornadoVM-CUDA-NVRTC-JNI] ERROR : nvrtcCompileProgram -> Returned: 6"));
        assertTrue(RuntimeOutputClassifier.isErrorLine("[TornadoVM-PTX-JNI] ERROR : cuLaunchKernel -> Returned: 701"));
        assertFalse(RuntimeOutputClassifier.isErrorLine("WARNING: Using incubator modules: jdk.incubator.vector"));
        assertFalse(RuntimeOutputClassifier.isErrorLine("__kernel void handleException(__global long *_kernel_context)"));
        assertFalse(RuntimeOutputClassifier.isErrorLine("\tat java.base/java.util.ArrayList.forEach(ArrayList.java:1604)"));
    }
}
