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

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.UnsupportedParameterTypeException;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationHarnessGenerator;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.harness.VerificationModel;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.JarPacker;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.JavacRunner;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.Processes;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.TornadoRunner;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.run.TornadoToolchain;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a candidate kernel with TornadoVM and compares its outputs with the original method's
 * (see {@link VerificationHarnessGenerator}); optionally times both. Used by AI-assisted
 * transformation; runs on a background thread and reads no PSI.
 */
public final class KernelVerifier {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = Logger.getInstance(KernelVerifier.class);
    private static final String CLASS_NAME = "InsightVerify";
    // The last probe that found no problem, so that TornadoVM is not started again for every
    // transformation; a problem is not kept, so that the user can fix it and try again
    private static volatile @Nullable CachedProbe lastProbe;
    // javac: "/tmp/.../InsightVerify.java:12: error: cannot find symbol"
    private static final Pattern JAVAC_ERROR = Pattern.compile("^.*\\.java:(\\d+): error: (.*)$");
    private static final int MAX_DETAIL = 1500;
    // "\tCUDA --  [NVIDIA CUDA] -- NVIDIA GeForce RTX 5080 Laptop GPU", after "Tornado device=0:0  (DEFAULT)"
    private static final Pattern DEVICE_LINE = Pattern.compile("^\\s*(\\w+)\\s+--\\s+\\[[^]]*]\\s+--\\s+(.+?)\\s*$");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    // The synthetic data sets of VerificationHarnessGenerator
    private static final String FIRST_SET_VALUES = "values from 0.5 to 1.5";
    private static final String SECOND_SET_VALUES = "values from -1 to 1 with zeros";
    private static final String SECOND_SET = "an odd length, " + SECOND_SET_VALUES;
    private static final String NOT_ON_DEVICE = "TornadoVM ran the kernel in Java instead of on the device (a bailout): "
            + "it could not compile it for the device. Use only what TornadoVM supports in kernels.";

    /**
     * The toolchain of {@code project}, or why there is none.
     *
     * @param toolchain where the harness is compiled and run, or {@code null}
     * @param problem   why kernels cannot be run, if {@code toolchain} is {@code null}
     */
    public record EnvironmentCheck(@Nullable TornadoToolchain toolchain, @Nullable String problem) {
    }

    /**
     * What TornadoVM itself says about the toolchain.
     *
     * @param problem why kernels cannot be run, or {@code null} if they can
     * @param device  TornadoVM's default device, or {@code null} if it cannot tell
     */
    public record Probe(@Nullable String problem, @Nullable Device device) {
    }

    public enum Status {
        /** The kernel's outputs match the original method's. */
        EQUAL,
        /** The kernel does not compile or does not run. */
        KERNEL_FAILED,
        /** The kernel runs, but an output differs. */
        DIFFERENT,
        /** The harness could not check the kernel; not the kernel's fault. */
        NOT_VERIFIED
    }

    /**
     * Median times of the timed runs, in nanoseconds.
     *
     * @param java         the original method in Java, or -1 if only the kernel was timed
     * @param kernel       one execution of the kernel's task graph, transfers included
     * @param deviceKernel the kernel on the device, as TornadoVM's profiler reports it
     * @param transfers    the data transfers, as TornadoVM's profiler reports them
     */
    public record Timing(long java, long kernel, long deviceKernel, long transfers) {
    }

    /**
     * @param status  the verdict
     * @param details what failed or differed, as messages for the model and the user
     * @param shape   the synthetic data the kernel was checked on, e.g. {@code "4096 elements of values
     *                from 0.5 to 1.5, and 4097 elements of values from -1 to 1 with zeros"}, or {@code null}
     * @param timing  the times, if timed runs were asked for and the kernel ran
     */
    public record Outcome(@NotNull Status status, @NotNull List<String> details, @Nullable String shape,
                          @Nullable Timing timing) {
        public Outcome {
            details = List.copyOf(details);
        }
    }

    /**
     * TornadoVM's default device, which runs the kernels.
     *
     * @param backend the backend, e.g. {@code CUDA}, {@code OPENCL}, {@code SPIRV}
     * @param name    the device, e.g. {@code NVIDIA GeForce RTX 5080 Laptop GPU}
     */
    public record Device(@NotNull String backend, @NotNull String name) {

        /** Whether the device runs Tile API kernels, which need the CUDA backend. */
        public boolean runsTiles() {
            return backend.equalsIgnoreCase("CUDA");
        }
    }

    private KernelVerifier() {
    }

    /**
     * Checks the project JDK against TornadoVM's JDK contract, as dynamic inspection does, then
     * asks {@code tornado --devices} for the default device. A problem here is the machine's, not
     * a kernel's: without this, every kernel would fail to run and go back to the model. A probe
     * without a problem is kept until TornadoVM or the JDK changes.
     */
    public static @NotNull Probe probe(@NotNull TornadoToolchain toolchain, @NotNull ProgressIndicator indicator) {
        List<Object> key = probeKey(toolchain);
        CachedProbe cached = lastProbe;
        if (cached != null && cached.key().equals(key)) {
            return cached.probe();
        }
        Probe probe = runProbe(toolchain, indicator);
        if (probe.problem() == null) {
            lastProbe = new CachedProbe(key, probe);
        }
        return probe;
    }

    // What a probe depends on: TornadoVM, its API jar's time, which changes when it is rebuilt, and the JDK
    private static List<Object> probeKey(TornadoToolchain toolchain) {
        String apiJar = toolchain.sdk().apiJar();
        return Arrays.asList(toolchain.sdk().homeValue(), apiJar, apiJar == null ? 0L : new File(apiJar).lastModified(),
                toolchain.jdkHome(), toolchain.jdkFeature());
    }

    private record CachedProbe(@NotNull List<Object> key, @NotNull Probe probe) {
    }

    private static @NotNull Probe runProbe(@NotNull TornadoToolchain toolchain, @NotNull ProgressIndicator indicator) {
        String problem = toolchain.checkJdk(indicator).problem(toolchain);
        if (problem != null) {
            return new Probe(problem, null);
        }
        Path workDir = null;
        try {
            workDir = FileUtil.createTempDirectory("tornado-insight-device", null, false).toPath();
            ProcessOutput output = Processes.run(toolchain.runner(workDir).deviceCheckCommand(), indicator,
                    TornadoRunner.PROBE_TIMEOUT_MS);
            if (output.isTimeout()) {
                return new Probe("`tornado --device` did not finish within "
                        + Processes.describeTimeout(TornadoRunner.PROBE_TIMEOUT_MS), null);
            }
            if (output.getExitCode() != 0) {
                return new Probe("`tornado --device` failed (exit code " + output.getExitCode() + "): "
                        + limit(lastLines(output.getStderr().isBlank() ? output.getStdout() : output.getStderr())), null);
            }
            return new Probe(null, defaultDevice(output.getStdout()));
        } catch (IOException | ExecutionException e) {
            LOG.warn("Could not list the TornadoVM devices", e);
            return new Probe("TornadoVM could not be started: " + e.getMessage(), null);
        } finally {
            if (workDir != null) {
                FileUtil.delete(workDir.toFile());
            }
        }
    }

    /** The default device in the output of {@code tornado --devices}, or {@code null}. */
    static @Nullable Device defaultDevice(@NotNull String devices) {
        List<String> lines = ANSI.matcher(devices).replaceAll("").lines().toList();
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).contains("(DEFAULT)")) {
                Matcher device = DEVICE_LINE.matcher(lines.get(i + 1));
                return device.matches() ? new Device(device.group(1).toUpperCase(), device.group(2)) : null;
            }
        }
        return null;
    }

    /** Checks that the project JDK and TornadoVM can run kernels. */
    public static @NotNull EnvironmentCheck environment(@NotNull Project project) {
        TornadoSdk sdk = TornadoSdk.getInstance();
        if (!sdk.isConfigured()) {
            return new EnvironmentCheck(null, "TORNADOVM_HOME is not set");
        }
        if (sdk.apiJar() == null) {
            return new EnvironmentCheck(null, "tornado-api.jar is missing from " + sdk.homeValue());
        }
        Sdk projectSdk = ProjectRootManager.getInstance(project).getProjectSdk();
        JavaSdkVersion version = projectSdk == null ? null : JavaSdkVersion.fromVersionString(projectSdk.getVersionString());
        if (projectSdk == null || projectSdk.getHomePath() == null || version == null
                || version.compareTo(JavaSdkVersion.JDK_21) < 0) {
            return new EnvironmentCheck(null, "the project JDK must be JDK 21 or later");
        }
        return new EnvironmentCheck(new TornadoToolchain(sdk, projectSdk.getHomePath(),
                version.getMaxLanguageLevel().toJavaVersion().feature), null);
    }

    /**
     * Verifies the kernel of {@code model} on arrays of about {@code length} elements, then, if
     * {@code runs} is positive, times it. {@code toolchain} should have passed {@link #probe}.
     */
    public static @NotNull Outcome verify(@NotNull TornadoToolchain toolchain, @NotNull VerificationModel model,
                                          int length, int warmups, int runs, @NotNull ProgressIndicator indicator) {
        return verify(toolchain, model, length, warmups, runs, true, indicator);
    }

    /**
     * {@link #verify(TornadoToolchain, VerificationModel, int, int, int, ProgressIndicator)}, timing
     * the original method too only if {@code timeJava}: its time is the same for every kernel of
     * one method, and for a slow method it takes most of the timing.
     */
    public static @NotNull Outcome verify(@NotNull TornadoToolchain toolchain, @NotNull VerificationModel model,
                                          int length, int warmups, int runs, boolean timeJava,
                                          @NotNull ProgressIndicator indicator) {
        Path[] workDir = new Path[1];
        Outcome outcome = run(toolchain, model, length, warmups, runs, timeJava, indicator, workDir);
        List<String> details = new ArrayList<>();
        for (String detail : outcome.details()) {
            details.add(withoutPaths(detail, toolchain, workDir[0], System.getProperty("user.home")));
        }
        return new Outcome(outcome.status(), details, outcome.shape(), outcome.timing());
    }

    /**
     * {@code text} with the machine's directories named instead of spelled out, since the details
     * go to the model: the work directory, TORNADOVM_HOME, the JDK and the user's home (which
     * holds the user's name).
     */
    static @NotNull String withoutPaths(@NotNull String text, @NotNull TornadoToolchain toolchain, @Nullable Path workDir,
                                        @Nullable String userHome) {
        String result = text;
        String[][] names = {
                {workDir == null ? null : workDir.toString(), "<work>"},
                {toolchain.sdk().home() == null ? null : toolchain.sdk().home().toString(), "$TORNADOVM_HOME"},
                {toolchain.jdkHome(), "$JAVA_HOME"},
                {userHome, "~"}};
        // In this order: the others may be under the user's home
        for (String[] name : names) {
            if (name[0] != null && name[0].length() > 1) {
                result = result.replace(name[0], name[1]);
            }
        }
        return result;
    }

    private static @NotNull Outcome run(@NotNull TornadoToolchain toolchain, @NotNull VerificationModel model, int length,
                                        int warmups, int runs, boolean timeJava, @NotNull ProgressIndicator indicator,
                                        Path[] usedDir) {
        VerificationHarnessGenerator.Source source;
        try {
            source = VerificationHarnessGenerator.generate(withClassName(model));
        } catch (UnsupportedParameterTypeException e) {
            return notVerified("Parameter '" + e.parameterName() + "' of type " + e.type() + " cannot be filled with test data yet");
        }
        Path workDir = null;
        try {
            workDir = FileUtil.createTempDirectory("tornado-insight-verify", null, false).toPath();
            usedDir[0] = workDir;
            Path sourceFile = workDir.resolve(CLASS_NAME + ".java");
            Files.writeString(sourceFile, source.source());
            Path classes = Files.createDirectory(workDir.resolve("classes"));

            JavacRunner.Result compiled = toolchain.compile(List.of(sourceFile.toString()), classes, indicator);
            if (compiled.status() != JavacRunner.Status.OK) {
                return compileFailure(compiled, source);
            }
            Path jar = workDir.resolve(CLASS_NAME + ".jar");
            JarPacker.pack(classes, CLASS_NAME, jar);

            TornadoRunner tornado = toolchain.runner(Files.createDirectory(workDir.resolve("run")));
            // Random for each run, so that the method under test cannot print a finding of its own
            String tag = Long.toHexString(RANDOM.nextLong());
            ProcessOutput output = Processes.run(tornado.runCommand(jar, String.valueOf(length), String.valueOf(warmups),
                    String.valueOf(runs), timeJava ? "java" : "kernel", tag), indicator, TornadoRunner.RUN_TIMEOUT_MS);
            if (output.isTimeout()) {
                return timedOut(output.getStdout(), tag, runs > 0, Processes.describeTimeout(TornadoRunner.RUN_TIMEOUT_MS));
            }
            return outcome(output.getStdout(), output.getStderr(), output.getExitCode(), tag);
        } catch (IOException | ExecutionException e) {
            LOG.warn("Could not verify the kernel", e);
            return notVerified("The kernel could not be run: " + e.getMessage());
        } finally {
            if (workDir != null && !FileUtil.delete(workDir.toFile())) {
                LOG.warn("Could not delete " + workDir);
            }
        }
    }

    private static VerificationModel withClassName(VerificationModel model) {
        if (model.kernel().className().equals(CLASS_NAME)) {
            return model;
        }
        throw new IllegalArgumentException("the kernel's harness class must be named " + CLASS_NAME);
    }

    /** The name the kernel's {@code HarnessModel} must give the harness class. */
    public static @NotNull String className() {
        return CLASS_NAME;
    }

    /**
     * The verdict on a harness that did not finish in {@code within}: the kernel's fault only if the
     * original method was not the one running, as the harness's output so far tells.
     *
     * @param tag   the tag the harness was given
     * @param timed whether the harness was timing, after verifying
     */
    static @NotNull Outcome timedOut(@NotNull String stdout, @NotNull String tag, boolean timed, @NotNull String within) {
        String prefix = VerificationHarnessGenerator.PREFIX + tag + " ";
        boolean original = false;
        boolean javaTiming = false;
        for (String line : stdout.lines().toList()) {
            if (line.startsWith(prefix + "data ")) {
                // A data set starts with the original method; "shape" follows once it has run
                original = true;
            } else if (line.startsWith(prefix + "shape ")) {
                original = false;
            } else if (line.equals(prefix + "equal")) {
                javaTiming = timed;
            } else if (line.equals(prefix + "timing-kernel")) {
                javaTiming = false;
            }
        }
        if (original) {
            return notVerified("The original method did not finish on the synthetic test data within " + within + ".");
        }
        if (javaTiming) {
            return notVerified("The original method did not finish its timed runs within " + within + ".");
        }
        return new Outcome(Status.KERNEL_FAILED, List.of("The kernel did not finish within " + within + "."), null, null);
    }

    /**
     * Reads the verdict from the harness's output: the lines with the tag it was given.
     */
    static @NotNull Outcome outcome(@NotNull String stdout, @NotNull String stderr, int exitCode, @NotNull String tag) {
        try {
            return findings(stdout, stderr, exitCode, VerificationHarnessGenerator.PREFIX + tag + " ");
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return notVerified("The test program's output could not be read: " + e.getMessage());
        }
    }

    private static Outcome findings(String stdout, String stderr, int exitCode, String prefix) {
        List<String> mismatches = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        String skipped = null;
        Timing timing = null;
        int set = 0;
        int sets = 0;
        int equal = 0;
        boolean different = false;
        for (String line : stdout.lines().toList()) {
            if (!line.startsWith(prefix)) {
                continue;
            }
            String finding = line.substring(prefix.length());
            String where = set == 2 ? "On the second data set (" + SECOND_SET + "): " : "";
            if (finding.startsWith("data ")) {
                set = Integer.parseInt(finding.substring("data ".length()).strip());
                sets++;
            } else if (finding.startsWith("original-failed ")) {
                return notVerified("The original method fails on the synthetic test data: "
                        + limit(finding.substring("original-failed ".length())));
            } else if (finding.startsWith("data-skipped ")) {
                skipped = limit(finding.substring("data-skipped ".length()));
                sets--;
            } else if (finding.startsWith("kernel-failed ")) {
                String error = limit(finding.substring("kernel-failed ".length()));
                String extra = RuntimeOutputClassifier.extractErrorLines(stdout, stderr);
                return new Outcome(Status.KERNEL_FAILED, List.of(where + "TornadoVM could not run the kernel: " + error
                        + (extra.isBlank() ? "" : "\n" + limit(extra))), null, null);
            } else if (finding.equals("not-on-device")) {
                return new Outcome(Status.KERNEL_FAILED, List.of(NOT_ON_DEVICE), null, null);
            } else if (finding.startsWith("shape ")) {
                String[] parts = finding.split(" ");
                int dimensions = Integer.parseInt(parts[1]);
                int side = Integer.parseInt(parts[2]);
                String size = dimensions == 1 ? side + " elements"
                        : (int) Math.pow(side, dimensions) + " elements (" + side + (dimensions == 2 ? "²" : "³") + ")";
                shapes.add(size + (set == 2 ? " of " + SECOND_SET_VALUES : " of " + FIRST_SET_VALUES));
            } else if (finding.startsWith("mismatch ")) {
                mismatches.add(where + "The kernel's output differs from the original method's: "
                        + finding.substring("mismatch ".length()) + ".");
            } else if (finding.equals("equal")) {
                equal++;
            } else if (finding.equals("different")) {
                different = true;
            } else if (finding.startsWith("time ")) {
                String[] t = finding.split(" ");
                timing = new Timing(Long.parseLong(t[1]), Long.parseLong(t[2]), Long.parseLong(t[3]), Long.parseLong(t[4]));
            }
        }
        String data = shapes.isEmpty() ? null : String.join(", and ", shapes)
                + (skipped == null ? "" : " (not " + SECOND_SET_VALUES + ": the method fails on them, " + skipped + ")");
        if (stderr.contains("[Bailout]")) {
            return new Outcome(Status.KERNEL_FAILED, List.of(NOT_ON_DEVICE), data, null);
        }
        RuntimeOutputClassifier.Verdict verdict = RuntimeOutputClassifier.classify(stdout, stderr, exitCode);
        if (verdict.deviceLimitation() != null) {
            return notVerified(verdict.deviceLimitation());
        }
        if (different) {
            return new Outcome(Status.DIFFERENT, mismatches, data, timing);
        }
        // Every data set that ran gave the same outputs
        if (sets > 0 && equal == sets && !verdict.failed()) {
            return new Outcome(Status.EQUAL, List.of(), data, timing);
        }
        String detail = verdict.errorDetail().isBlank() ? lastLines(stderr.isBlank() ? stdout : stderr) : verdict.errorDetail();
        return new Outcome(Status.KERNEL_FAILED, List.of("The kernel failed at run time (exit code " + exitCode + "): "
                + limit(detail)), data, null);
    }

    // Errors in the kernel's lines are the model's to fix; elsewhere the harness copied something javac rejects
    private static Outcome compileFailure(JavacRunner.Result compiled, VerificationHarnessGenerator.Source source) {
        if (compiled.status() != JavacRunner.Status.ERRORS) {
            return notVerified("javac could not be run: " + limit(compiled.detail()));
        }
        String[] sourceLines = source.source().split("\n", -1);
        List<String> kernelErrors = new ArrayList<>();
        List<String> otherErrors = new ArrayList<>();
        for (String line : compiled.detail().lines().toList()) {
            Matcher error = JAVAC_ERROR.matcher(line);
            if (!error.matches()) {
                continue;
            }
            int number = Integer.parseInt(error.group(1));
            String code = number - 1 < sourceLines.length ? sourceLines[number - 1].strip() : "";
            String message = error.group(2) + (code.isEmpty() ? "" : " in `" + code + "`");
            if (number >= source.kernelFirstLine() && number <= source.kernelLastLine()) {
                kernelErrors.add("javac: " + message);
            } else {
                otherErrors.add(message);
            }
        }
        if (!kernelErrors.isEmpty()) {
            return new Outcome(Status.KERNEL_FAILED, kernelErrors.subList(0, Math.min(5, kernelErrors.size())), null, null);
        }
        LOG.info("Verification harness does not compile:\n" + compiled.detail());
        return notVerified("The test program does not compile"
                + (otherErrors.isEmpty() ? "" : ": " + limit(String.join("; ", otherErrors))));
    }

    private static Outcome notVerified(String reason) {
        return new Outcome(Status.NOT_VERIFIED, List.of(reason), null, null);
    }

    private static String lastLines(String text) {
        List<String> lines = text.strip().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 10), lines.size()));
    }

    private static String limit(String text) {
        String stripped = text.strip();
        return stripped.length() > MAX_DETAIL ? stripped.substring(0, MAX_DETAIL) + "…" : stripped;
    }
}
