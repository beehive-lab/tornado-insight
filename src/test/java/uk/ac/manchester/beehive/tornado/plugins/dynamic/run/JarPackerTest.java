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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class JarPackerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path classes;

    private void classFiles(String... names) throws IOException {
        classes = temp.newFolder("classes").toPath();
        for (String name : names) {
            Files.write(classes.resolve(name), new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        }
    }

    @Test
    public void nestedAndAnonymousClassesGoIntoTheJarOfTheirOuterClass() throws IOException {
        classFiles("vaddAb12C.class", "vaddAb12C$1.class", "vaddAb12C$Inner.class",
                "vaddAb12CD.class", "mulXy34Z.class", "mulXy34Z$1.class");

        Path jar = temp.getRoot().toPath().resolve("vaddAb12C.jar");
        JarPacker.pack(classes, "vaddAb12C", jar);

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            assertEquals("vaddAb12C", jarFile.getManifest().getMainAttributes().getValue("Main-Class"));
            List<String> entries = new ArrayList<>();
            for (JarEntry entry : Collections.list(jarFile.entries())) {
                entries.add(entry.getName());
            }
            assertEquals(List.of("META-INF/MANIFEST.MF", "vaddAb12C$1.class", "vaddAb12C$Inner.class", "vaddAb12C.class"),
                    entries);
        }
    }

    @Test
    public void missingMainClassIsAnError() throws IOException {
        classFiles("mulXy34Z.class");
        Path jar = temp.getRoot().toPath().resolve("vaddAb12C.jar");
        assertThrows(IOException.class, () -> JarPacker.pack(classes, "vaddAb12C", jar));
    }
}
