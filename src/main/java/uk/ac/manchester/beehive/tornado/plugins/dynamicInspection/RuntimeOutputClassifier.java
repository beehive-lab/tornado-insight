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

/**
 * Classifies the output of a {@code tornado --printKernel -jar <harness>} run:
 * whether it failed, whether the failure is a device limitation, and which lines
 * explain it. Pure string processing over stdout/stderr, so it is unit tested
 * without an IDE.
 */
public final class RuntimeOutputClassifier {

    private RuntimeOutputClassifier() {
    }

    /**
     * Patterns that indicate a TornadoVM runtime error in the process output.
     * These cover Java exceptions, OpenCL JNI errors, SPIR-V errors, and PTX/CUDA errors.
     */
    private static final String[] ERROR_PATTERNS = {
            "Exception",
            "[TornadoVM-OCL-JNI] ERROR",
            "[TornadoVM-SPIRV-JNI] ERROR",
            "[TornadoVM-PTX-JNI] ERROR",
            "CL_INVALID_KERNEL",
            "CL_BUILD_PROGRAM_FAILURE",
            "CL_OUT_OF_RESOURCES",
            "CL_MEM_OBJECT_ALLOCATION_FAILURE",
            "CL_INVALID_PROGRAM",
            "CL_INVALID_WORK_GROUP_SIZE",
            "OpenCL Error",
            "CUDA_ERROR",
            "TornadoInternalError",
            "TornadoBailoutRuntimeException",
            "TornadoDeviceFP16NotSupported",
            "TornadoMemoryException",
    };

    /**
     * Detects whether the TornadoVM process output contains runtime errors.
     * Checks for Java exceptions as well as OpenCL/SPIR-V/PTX JNI-level errors
     * that do not manifest as Java exceptions.
     */
    public static boolean detectRuntimeError(String stdout, String stderr) {
        String combined = stdout + "\n" + stderr;
        for (String pattern : ERROR_PATTERNS) {
            if (combined.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognises runtime failures that are caused by the target device lacking
     * a capability the kernel needs (rather than a bug in the kernel or the
     * generated harness), and returns a plain-language explanation. The harness
     * was generated correctly in these cases - the kernel simply cannot run on
     * this device - so the caller reports it as a warning instead of an error.
     *
     * <p>Note: this deliberately matches only TornadoVM's explicit
     * "unsupported feature/type" messages, not generic OpenCL compiler
     * exceptions (e.g. a {@code clBuildProgram} failure). A genuine compiler
     * exception still surfaces as an error.
     *
     * @return a user-facing explanation, or {@code null} if the failure is not
     *         a recognised device-capability limitation.
     */
    public static String describeDeviceLimitation(String stdout, String stderr) {
        String combined = stdout + "\n" + stderr;
        String lower = combined.toLowerCase();

        // 64-bit floating point (FP64 / double precision).
        if (lower.contains("cl_khr_fp64") || lower.contains("fp64")
                || lower.contains("double precision")
                || lower.contains("64-bit floating point")) {
            return "this device does not support 64-bit floating point (FP64 / double precision). "
                    + "Your kernel is valid, but TornadoVM cannot compile it for this device. "
                    + "This is a device limitation, not an error in your kernel.";
        }

        // Floating-point atomic operations (e.g. atomicAdd on a float/double).
        if (combined.contains("does not support floating point operations")) {
            return "this device does not support floating-point atomic operations "
                    + "(for example atomicAdd on a float or double). "
                    + "Your kernel is valid, but TornadoVM cannot compile it for this device. "
                    + "This is a device limitation, not an error in your kernel.";
        }

        // 16-bit floating point (FP16 / half).
        if (combined.contains("TornadoDeviceFP16NotSupported")) {
            return "this device does not support 16-bit floating point (FP16 / half). "
                    + "Your kernel is valid, but TornadoVM cannot compile it for this device. "
                    + "This is a device limitation, not an error in your kernel.";
        }

        return null;
    }

    /**
     * Extracts error-relevant lines from the process output, filtering out
     * noise like normal kernel output or incubator warnings.
     * Returns a consolidated error summary string.
     */
    public static String extractErrorLines(String stdout, String stderr) {
        StringBuilder errors = new StringBuilder();
        String combined = stdout + "\n" + stderr;
        for (String line : combined.split("\n")) {
            for (String pattern : ERROR_PATTERNS) {
                if (line.contains(pattern)) {
                    errors.append(line.trim()).append("\n");
                    break;
                }
            }
        }
        return errors.toString().trim();
    }

    /**
     * Removes error/diagnostic lines from stdout so the kernel code section
     * only contains the actual generated kernel, not the JNI error messages
     * that TornadoVM may intermix with kernel output.
     */
    public static String stripErrorLines(String stdout) {
        if (stdout == null) return "";
        StringBuilder clean = new StringBuilder();
        for (String line : stdout.split("\n")) {
            boolean isErrorLine = false;
            for (String pattern : ERROR_PATTERNS) {
                if (line.contains(pattern)) {
                    isErrorLine = true;
                    break;
                }
            }
            // Also strip JNI notify lines that precede the actual error
            if (!isErrorLine && !line.contains("[JNI] uk.ac.manchester.tornado.drivers.")) {
                clean.append(line).append("\n");
            }
        }
        return clean.toString().trim();
    }
}
