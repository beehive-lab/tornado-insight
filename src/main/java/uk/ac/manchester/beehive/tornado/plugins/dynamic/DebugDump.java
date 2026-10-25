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

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The internal debug dump: keeps the test program and the kernel of every task of the latest
 * dynamic inspection run, which otherwise live in a temporary folder that is deleted.
 * <p>
 * The files go to {@code tornadoinsight} in the operating system's temporary folder
 * ({@code java.io.tmpdir}: {@code /tmp} on Linux, {@code %TEMP%} on Windows,
 * {@code /var/folders/...} on macOS). Each run replaces the files of the previous one.
 * </p>
 */
final class DebugDump {

    static final String FOLDER = "tornadoinsight";

    private final Path directory;

    DebugDump(@NotNull Path directory) {
        this.directory = directory;
    }

    /** The dump in the operating system's temporary folder. */
    static @NotNull DebugDump inTemporaryFolder() {
        return new DebugDump(Path.of(System.getProperty("java.io.tmpdir"), FOLDER));
    }

    @NotNull Path directory() {
        return directory;
    }

    /** Creates the folder, or empties it of the previous run's files. */
    void clear() throws IOException {
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).filter(f -> !f.equals(directory)).toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(directory);
    }

    /** Writes the generated test program as {@code <className>.java}. */
    @NotNull Path writeTest(@NotNull String className, @NotNull String source) throws IOException {
        return Files.writeString(directory.resolve(className + ".java"), source);
    }

    /** Writes the kernel TornadoVM generated for the test as {@code <className>.kernel.<cl|cu|ptx|txt>}. */
    @NotNull Path writeKernel(@NotNull String className, @NotNull String kernelSource) throws IOException {
        return Files.writeString(directory.resolve(className + ".kernel." + extension(kernelSource)), kernelSource);
    }

    /** The file extension for the backend that produced {@code kernelSource}. */
    static @NotNull String extension(@NotNull String kernelSource) {
        if (kernelSource.contains(".version") && kernelSource.contains(".target")) {
            return "ptx";
        }
        if (kernelSource.contains("__global__")) {
            return "cu";
        }
        if (kernelSource.contains("__kernel")) {
            return "cl";
        }
        return "txt";
    }
}
