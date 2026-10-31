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

package uk.ac.manchester.beehive.tornado.plugins.dynamic.run;

import com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoJdkContract;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A TornadoVM SDK with the project JDK: checks that they work together, compiles generated
 * programs against the SDK and runs them with its launcher. Shared by dynamic inspection and
 * the verification of transformed kernels, so both refuse the same JDKs and see the same jars.
 *
 * @param sdk        the TornadoVM installation
 * @param jdkHome    the project JDK's home, or {@code null} if the SDK does not say
 * @param jdkFeature the project JDK's feature version
 */
public record TornadoToolchain(@NotNull TornadoSdk sdk, @Nullable String jdkHome, int jdkFeature) {

    /**
     * How the project JDK fits the SDK's JDK contract.
     */
    public enum JdkFit {
        /** The SDK runs on the project JDK. */
        OK,
        /** The contract could not be read; {@link JdkCheck#failure()} says why. */
        UNKNOWN,
        /** The project JDK's version is not one the SDK runs on. */
        INCOMPATIBLE,
        /** The SDK ships its own Graal compiler, which clashes with a GraalVM JDK's. */
        GRAALVM
    }

    /**
     * @param fit      the verdict
     * @param contract the SDK's contract, or {@code null} if it could not be read
     * @param failure  why the contract could not be read, for {@link JdkFit#UNKNOWN}
     */
    public record JdkCheck(@NotNull JdkFit fit, @Nullable TornadoJdkContract contract, @Nullable String failure) {

        /** Why the project JDK cannot run the SDK, in a sentence for the user; {@code null} if it can. */
        public @Nullable String problem(@NotNull TornadoToolchain toolchain) {
            return switch (fit) {
                case OK -> null;
                case UNKNOWN -> "TornadoVM's JDK requirement could not be read: " + failure;
                case INCOMPATIBLE -> "TornadoVM at " + toolchain.sdk().homeValue() + " needs "
                        + Objects.requireNonNull(contract).requirement() + ", and the project JDK is JDK " + toolchain.jdkFeature();
                case GRAALVM -> "the project JDK at " + toolchain.jdkHome() + " is a GraalVM build, whose Graal "
                        + "compiler clashes with the one TornadoVM bundles; set a standard OpenJDK build, "
                        + Objects.requireNonNull(contract).requirement() + ", as the project JDK";
            };
        }
    }

    /**
     * Checks the project JDK against the SDK's JDK contract. Only the feature version is
     * compared, and the vendor is ignored, except that a GraalVM JDK is refused when the SDK
     * ships its own Graal compiler: TornadoVM fails deep inside, or crashes, otherwise.
     *
     * @throws com.intellij.openapi.progress.ProcessCanceledException if the user cancels
     */
    public @NotNull JdkCheck checkJdk(@NotNull ProgressIndicator indicator) {
        TornadoSdk.JdkContractResult result = sdk.jdkContract(indicator, jdkHome, TornadoRunner.PROBE_TIMEOUT_MS);
        TornadoJdkContract contract = result.contract();
        if (contract == null) {
            return new JdkCheck(JdkFit.UNKNOWN, null, String.valueOf(result.failure()));
        }
        if (!contract.accepts(jdkFeature)) {
            return new JdkCheck(JdkFit.INCOMPATIBLE, contract, null);
        }
        if (contract.vendorsGraal() && isGraalVm(jdkHome)) {
            return new JdkCheck(JdkFit.GRAALVM, contract, null);
        }
        return new JdkCheck(JdkFit.OK, contract, null);
    }

    /**
     * Whether the JDK at {@code home} is GraalVM (Community or Oracle), from the {@code release}
     * file in its home (see {@link TornadoJdkContract#isGraalVmRelease}).
     */
    public static boolean isGraalVm(@Nullable String home) {
        if (home == null || home.isEmpty()) {
            return false;
        }
        Path release = Path.of(home, "release");
        if (!Files.isRegularFile(release)) {
            return false;
        }
        try {
            return TornadoJdkContract.isGraalVmRelease(Files.readAllLines(release));
        } catch (IOException ignore) {
            // Cannot read it - assume a stock JDK rather than block the run.
        }
        return false;
    }

    /**
     * What generated programs compile against: the jars they need first, then every other jar
     * of the SDK, so that imports of the libraries it ships (e.g. tornado-mlx) resolve.
     */
    public @NotNull List<String> classpath() {
        Set<String> classpath = new LinkedHashSet<>();
        for (String jar : new String[]{sdk.apiJar(), sdk.matricesJar(), sdk.unitTestsJar()}) {
            if (jar != null) {
                classpath.add(jar);
            }
        }
        classpath.addAll(sdk.jars());
        return new ArrayList<>(classpath);
    }

    /**
     * Compiles {@code javaFiles} into {@code outputDir} with the project JDK (see {@link JavacRunner}).
     *
     * @throws com.intellij.openapi.progress.ProcessCanceledException if the user cancels
     */
    public @NotNull JavacRunner.Result compile(@NotNull List<String> javaFiles, @NotNull Path outputDir,
                                               @NotNull ProgressIndicator indicator) {
        return JavacRunner.run(JavacRunner.commandLine(String.valueOf(jdkHome), JavacRunner.release(jdkFeature),
                classpath(), outputDir, javaFiles, sdk.isWindows()), indicator);
    }

    /** The launcher, run in {@code workDir} (see {@link TornadoRunner}). */
    public @NotNull TornadoRunner runner(@NotNull Path workDir) {
        return new TornadoRunner(sdk, jdkHome, workDir);
    }
}
