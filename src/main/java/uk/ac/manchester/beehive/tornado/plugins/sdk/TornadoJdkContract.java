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

package uk.ac.manchester.beehive.tornado.plugins.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK compatibility contract of a TornadoVM SDK. {@code floor} is the lowest
 * supported JDK feature version; when {@code pinned} the SDK was built with
 * preview features enabled and runs on exactly that release, nothing newer.
 * {@code vendorsGraal} is set for TornadoVM &ge; 6.0.0, which bundles its own
 * Graal compiler and JVMCI as application modules &mdash; incompatible with a
 * GraalVM JDK that already ships those modules (see {@link #isGraalVmRelease}).
 * <p>
 * The parsers here work on file contents and process output only; reading the
 * files and running {@code tornado --version} is left to the caller.
 * </p>
 */
public record TornadoJdkContract(int floor, boolean pinned, boolean vendorsGraal) {

    // TornadoVM >= 6.0.0 records its JDK compatibility contract in
    // $TORNADOVM_HOME/etc/tornado.jdk, written when the SDK was compiled:
    //   tornado.jdk.floor=<n>       lowest supported JDK feature version
    //   tornado.jdk.preview=<bool>  built with preview features -> pinned to <n>
    // This mirrors bin/tornado.py::checkCompatibilityWithTornadoVM. Held as path
    // segments so it resolves correctly on Windows too.
    public static final String[] CONTRACT_FILE_PATH = {"etc", "tornado.jdk"};

    // Pre-6.0.0 SDKs ship no contract file; 'tornado --version' instead embeds
    // the target JDK in a line like "version=4.0.1-jdk21".
    private static final Pattern TORNADO_JDK_PATTERN =
            Pattern.compile("(?im)^\\s*version\\s*=\\s*\\S*-jdk(\\d+)");

    // When 'tornado --version' itself rejects the current JDK, its stderr names the
    // JDK it was built against, e.g.
    //   "[ERROR] TornadoVM is only compatible with JDK version 25"
    //   "TornadoVM supports only JDK version 25"
    private static final Pattern TORNADO_REQUIRED_JDK_PATTERN =
            Pattern.compile("(?im)(?:compatible with|supports only)\\s+JDK\\s+version\\s+(\\d+)");

    public boolean accepts(int projectJdk) {
        return pinned ? projectJdk == floor : projectJdk >= floor;
    }

    public @NotNull String requirement() {
        return pinned
                ? "JDK " + floor + " exactly (this build has preview features enabled)"
                : "JDK " + floor + " or newer";
    }

    public @NotNull String source() {
        return vendorsGraal ? "etc/tornado.jdk" : "tornado --version";
    }

    /**
     * Parses the contents of {@code etc/tornado.jdk} (TornadoVM &ge; 6.0.0).
     *
     * @return the contract, or {@code null} if the file has no usable floor
     */
    public static @Nullable TornadoJdkContract parseContractFile(@NotNull String contents) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(contents));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        String floor = props.getProperty("tornado.jdk.floor");
        if (floor == null || floor.isBlank()) {
            return null;
        }
        try {
            return new TornadoJdkContract(
                    Integer.parseInt(floor.trim()),
                    Boolean.parseBoolean(props.getProperty("tornado.jdk.preview", "false").trim()),
                    true);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses the combined stdout and stderr of {@code tornado --version}, for SDKs
     * without {@code etc/tornado.jdk}. Reads the target JDK from the
     * {@code version=<x>-jdk<n>} line (such SDKs are pinned to that single
     * release). If {@code tornado} itself rejected the running JDK, its output
     * names the one it wants, which is returned as the target instead.
     *
     * @return the contract, or {@code null} if the output has no recognisable marker
     */
    public static @Nullable TornadoJdkContract parseVersionOutput(@NotNull String output) {
        // Happy path: the legacy version line carries the target JDK.
        Matcher matcher = TORNADO_JDK_PATTERN.matcher(output);
        if (matcher.find()) {
            try {
                return new TornadoJdkContract(Integer.parseInt(matcher.group(1)), true, false);
            } catch (NumberFormatException ignore) {
                // Fall through to the structured-failure handling below.
            }
        }

        // Failure path: tornado itself rejected the current JDK and named the
        // version it wants. Surface that as the target JDK so the upstream
        // comparison can produce a precise mismatch message.
        Matcher requiredJdk = TORNADO_REQUIRED_JDK_PATTERN.matcher(output);
        if (requiredJdk.find()) {
            try {
                return new TornadoJdkContract(Integer.parseInt(requiredJdk.group(1)), true, false);
            } catch (NumberFormatException ignore) {
                // Fall through.
            }
        }
        return null;
    }

    /**
     * Detects a GraalVM JDK (Community or Oracle) from the lines of the
     * {@code release} file in its home directory. TornadoVM &ge; 6.0.0 vendors its
     * own Graal compiler and, on JDK 22-26, patches {@code jdk.internal.vm.ci} with
     * a frozen JDK-21 copy; a GraalVM JDK ships both with a different JVMCI ABI, so
     * the patched-in classes fail HotSpot's field-offset checks and the JVM aborts.
     */
    public static boolean isGraalVmRelease(@NotNull List<String> releaseLines) {
        for (String line : releaseLines) {
            if (line.startsWith("GRAALVM_VERSION=")) {
                return true;
            }
            if (line.startsWith("JAVA_RUNTIME_VERSION=") && line.contains("-jvmci-")) {
                return true;
            }
        }
        return false;
    }
}
