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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Classifies the output of a {@code tornado --printKernel -jar <harness>} run:
 * whether it failed, whether the failure is a device limitation, and which lines
 * explain it. Pure string processing over stdout/stderr, so it is unit tested
 * without an IDE.
 * <p>
 * The run prints the generated kernel source to stdout, and that source is not
 * evidence of anything: an OpenCL kernel for a device with FP64 support starts
 * with {@code #pragma OPENCL EXTENSION cl_khr_fp64 : enable}, and kernel or
 * variable names may contain words like "Exception". Classification therefore
 * only looks at <em>diagnostic lines</em>: every line of stderr (exceptions,
 * stack traces and build logs go there), and the stdout lines that are
 * diagnostics themselves (TornadoVM's JNI drivers print their errors to stdout,
 * tagged {@code [TornadoVM-<backend>-JNI] ERROR} or {@code [JNI]}).
 * </p>
 */
public final class RuntimeOutputClassifier {

    /**
     * The verdict on one run.
     *
     * @param failed           whether the run failed
     * @param deviceLimitation a user-facing explanation when the failure is a
     *                         device-capability limitation, otherwise {@code null}
     * @param errorDetail      the lines explaining the failure (empty if it did not fail)
     * @param kernelSource     stdout without diagnostic lines, i.e. the printed kernel
     */
    public record Verdict(boolean failed, @Nullable String deviceLimitation, @NotNull String errorDetail,
                          @NotNull String kernelSource) {
    }

    // A Java exception or error: "Exception in thread "main" x.y.Foo: ...",
    // "Caused by: x.y.Foo: ...", or an exception/error class name followed by its message.
    private static final Pattern EXCEPTION_LINE =
            Pattern.compile("^\\s*(Exception in thread|Caused by:|[\\w.$]+(Exception|Error):)");

    // Errors reported by the native drivers, e.g. "[TornadoVM-OCL-JNI] ERROR : clBuildProgram -> ..."
    // (OCL, SPIRV, PTX, CUDA, CUDA-NVRTC, ...).
    private static final Pattern JNI_ERROR = Pattern.compile("\\[TornadoVM-[\\w-]+] ERROR");

    // Other runtime lines the drivers print to stdout, e.g. "[JNI] uk.ac.manchester.tornado.drivers...".
    private static final Pattern TAGGED_RUNTIME_LINE = Pattern.compile("^\\s*\\[(JNI|ERROR|TornadoVM[\\w-]*)]");

    /**
     * Error codes and TornadoVM exception names that mark a failure wherever they
     * appear in a diagnostic line.
     */
    private static final String[] ERROR_MARKERS = {
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
            "TornadoDeviceFP64NotSupported",
            "TornadoMemoryException",
    };

    // TornadoVM's FP64 checks ("The current OpenCL device (...) does not support FP64"),
    // and an OpenCL compiler rejecting doubles ("use of type 'double' requires cl_khr_fp64 ...").
    private static final Pattern FP64_LIMITATION = Pattern.compile(
            "TornadoDeviceFP64NotSupported|does not support (FP64|double precision|64-bit floating point)"
                    + "|requires cl_khr_fp64",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FP16_LIMITATION =
            Pattern.compile("TornadoDeviceFP16NotSupported|does not support FP16");

    private static final String FLOAT_ATOMICS_LIMITATION = "does not support floating point operations";

    private RuntimeOutputClassifier() {
    }

    /**
     * Classifies a finished run.
     *
     * @param exitCode the process exit code; a non-zero code is a failure even
     *                 when no diagnostic line is recognised (e.g. a JVM crash)
     */
    public static @NotNull Verdict classify(@Nullable String stdout, @Nullable String stderr, int exitCode) {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        List<String> diagnostics = diagnosticLines(stdout, stderr);
        String errorLines = errorLines(diagnostics);
        boolean failed = exitCode != 0 || !errorLines.isEmpty();
        if (!failed) {
            return new Verdict(false, null, "", stripErrorLines(stdout));
        }
        String detail = !errorLines.isEmpty() ? errorLines
                : "tornado exited with code " + exitCode + (stderr.isBlank() ? "" : ":\n" + stderr.strip());
        return new Verdict(true, describeDeviceLimitation(diagnostics), detail, stripErrorLines(stdout));
    }

    /**
     * Returns whether the run output contains a runtime error. The exit code is
     * not considered; see {@link #classify}.
     */
    public static boolean detectRuntimeError(@NotNull String stdout, @NotNull String stderr) {
        return !errorLines(diagnosticLines(stdout, stderr)).isEmpty();
    }

    /**
     * Recognises runtime failures that are caused by the target device lacking
     * a capability the kernel needs (rather than a bug in the kernel or the
     * generated harness), and returns a plain-language explanation. The harness
     * was generated correctly in these cases - the kernel simply cannot run on
     * this device - so the caller reports it as a warning instead of an error.
     *
     * <p>Note: this deliberately matches only TornadoVM's explicit
     * "unsupported feature/type" diagnostics, never extension names or types in
     * the printed kernel, nor generic compiler exceptions (e.g. a
     * {@code clBuildProgram} failure). A genuine compiler exception still
     * surfaces as an error.
     *
     * @return a user-facing explanation, or {@code null} if the failure is not
     *         a recognised device-capability limitation.
     */
    public static @Nullable String describeDeviceLimitation(@NotNull String stdout, @NotNull String stderr) {
        return describeDeviceLimitation(diagnosticLines(stdout, stderr));
    }

    private static @Nullable String describeDeviceLimitation(List<String> diagnostics) {
        String text = String.join("\n", diagnostics);

        // 64-bit floating point (FP64 / double precision).
        if (FP64_LIMITATION.matcher(text).find()) {
            return "this device does not support 64-bit floating point (FP64 / double precision). "
                    + "Your kernel is valid, but TornadoVM cannot compile it for this device. "
                    + "This is a device limitation, not an error in your kernel.";
        }

        // Floating-point atomic operations (e.g. atomicAdd on a float/double).
        if (text.contains(FLOAT_ATOMICS_LIMITATION)) {
            return "this device does not support floating-point atomic operations "
                    + "(for example atomicAdd on a float or double). "
                    + "Your kernel is valid, but TornadoVM cannot compile it for this device. "
                    + "This is a device limitation, not an error in your kernel.";
        }

        // 16-bit floating point (FP16 / half).
        if (FP16_LIMITATION.matcher(text).find()) {
            return "this device does not support 16-bit floating point (FP16 / half). "
                    + "Your kernel is valid, but TornadoVM cannot compile it for this device. "
                    + "This is a device limitation, not an error in your kernel.";
        }

        return null;
    }

    /**
     * Extracts the error lines from the diagnostic lines of the output, filtering
     * out the printed kernel and noise like JVM warnings.
     */
    public static @NotNull String extractErrorLines(@NotNull String stdout, @NotNull String stderr) {
        return errorLines(diagnosticLines(stdout, stderr));
    }

    /**
     * Removes error/diagnostic lines from stdout so the kernel code section
     * only contains the actual generated kernel, not the JNI error messages
     * that TornadoVM may intermix with kernel output.
     */
    public static @NotNull String stripErrorLines(@Nullable String stdout) {
        if (stdout == null) return "";
        StringBuilder clean = new StringBuilder();
        for (String line : stdout.split("\n")) {
            if (!isStdoutDiagnostic(line)) {
                clean.append(line).append("\n");
            }
        }
        return clean.toString().trim();
    }

    /**
     * Returns whether {@code line} reports an error.
     */
    static boolean isErrorLine(@NotNull String line) {
        if (EXCEPTION_LINE.matcher(line).find() || JNI_ERROR.matcher(line).find()) {
            return true;
        }
        for (String marker : ERROR_MARKERS) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStdoutDiagnostic(String line) {
        return isErrorLine(line) || TAGGED_RUNTIME_LINE.matcher(line).find();
    }

    private static List<String> diagnosticLines(String stdout, String stderr) {
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            if (isStdoutDiagnostic(line)) {
                lines.add(line);
            }
        }
        for (String line : stderr.split("\n")) {
            lines.add(line);
        }
        return lines;
    }

    private static String errorLines(List<String> diagnostics) {
        StringBuilder errors = new StringBuilder();
        for (String line : diagnostics) {
            if (isErrorLine(line)) {
                errors.append(line.trim()).append("\n");
            }
        }
        return errors.toString().trim();
    }
}
