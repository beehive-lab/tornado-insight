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

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Packs a compiled harness into an executable jar for {@code tornado -jar}.
 */
public final class JarPacker {

    private JarPacker() {
    }

    /**
     * Returns the class files in {@code classesDir} that belong to the top-level
     * class {@code className} of the default package: the class itself and its
     * nested, local and anonymous classes ({@code className$...}), sorted by name.
     */
    public static @NotNull List<Path> classFilesOf(@NotNull Path classesDir, @NotNull String className) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(classesDir, "*.class")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.equals(className + ".class") || name.startsWith(className + "$")) {
                    files.add(file);
                }
            }
        }
        Collections.sort(files);
        return files;
    }

    /**
     * Writes {@code jarFile} with every class file of {@code className} (see
     * {@link #classFilesOf}) and a manifest naming it as the main class.
     *
     * @throws IOException if the classes cannot be read, the main class is
     *                     missing, or the jar cannot be written
     */
    public static void pack(@NotNull Path classesDir, @NotNull String className, @NotNull Path jarFile) throws IOException {
        List<Path> classFiles = classFilesOf(classesDir, className);
        if (!classFiles.contains(classesDir.resolve(className + ".class"))) {
            throw new IOException("compiled class " + className + " not found in " + classesDir);
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, className);
        try (OutputStream out = Files.newOutputStream(jarFile);
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            for (Path classFile : classFiles) {
                jar.putNextEntry(new JarEntry(classFile.getFileName().toString()));
                Files.copy(classFile, jar);
                jar.closeEntry();
            }
        }
    }
}
