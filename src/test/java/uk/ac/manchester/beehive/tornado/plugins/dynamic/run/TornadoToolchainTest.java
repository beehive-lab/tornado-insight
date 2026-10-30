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

import com.intellij.openapi.progress.EmptyProgressIndicator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TornadoToolchain against a fake SDK (TornadoVM 7.3.1, JDK 22 or newer) and fake JDK homes.
 */
public class TornadoToolchainTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path home;
    private TornadoSdk sdk;

    @Before
    public void createFakeSdk() throws IOException {
        home = temp.newFolder("tornadovm-7.3.1").toPath();
        Path jars = Files.createDirectories(home.resolve("share/java/tornado"));
        for (String jar : List.of("tornado-api-7.3.1.jar", "tornado-matrices-7.3.1.jar", "tornado-unittests-7.3.1.jar",
                "tornado-annotation-7.3.1.jar", "tornado-mlx-7.3.1.jar")) {
            Files.createFile(jars.resolve(jar));
        }
        Files.createDirectories(home.resolve("etc"));
        Files.writeString(home.resolve("etc/tornado.jdk"), "tornado.jdk.floor=22\ntornado.jdk.preview=false\n");
        sdk = TornadoSdk.forHome(home.toString(), false);
    }

    private String jdk(String name, String release) throws IOException {
        Path jdk = temp.newFolder(name).toPath();
        Files.writeString(jdk.resolve("release"), release);
        return jdk.toString();
    }

    @Test
    public void aStockJdkInTheContractRunsTheSdk() throws IOException {
        TornadoToolchain toolchain = new TornadoToolchain(sdk, jdk("temurin-25", "JAVA_VERSION=\"25\"\n"), 25);
        TornadoToolchain.JdkCheck check = toolchain.checkJdk(new EmptyProgressIndicator());
        assertEquals(TornadoToolchain.JdkFit.OK, check.fit());
        assertNull(check.failure());
    }

    @Test
    public void anOlderJdkIsRefusedWithTheRequirement() throws IOException {
        TornadoToolchain toolchain = new TornadoToolchain(sdk, jdk("temurin-21", "JAVA_VERSION=\"21\"\n"), 21);
        TornadoToolchain.JdkCheck check = toolchain.checkJdk(new EmptyProgressIndicator());
        assertEquals(TornadoToolchain.JdkFit.INCOMPATIBLE, check.fit());
        assertEquals("JDK 22 or newer", check.contract().requirement());
    }

    @Test
    public void aGraalVmJdkIsRefused() throws IOException {
        String graal = jdk("graalvm-25", "JAVA_VERSION=\"25\"\nGRAALVM_VERSION=\"25.0.1\"\n");
        TornadoToolchain toolchain = new TornadoToolchain(sdk, graal, 25);
        TornadoToolchain.JdkCheck check = toolchain.checkJdk(new EmptyProgressIndicator());
        assertEquals(TornadoToolchain.JdkFit.GRAALVM, check.fit());
        assertTrue(check.contract().vendorsGraal());
    }

    @Test
    public void theClasspathHasTheNeededJarsFirstThenTheRestOfTheSdk() {
        Path jars = home.resolve("share/java/tornado");
        assertEquals(List.of(jars.resolve("tornado-api-7.3.1.jar").toString(),
                        jars.resolve("tornado-matrices-7.3.1.jar").toString(),
                        jars.resolve("tornado-unittests-7.3.1.jar").toString(),
                        jars.resolve("tornado-annotation-7.3.1.jar").toString(),
                        jars.resolve("tornado-mlx-7.3.1.jar").toString()),
                new TornadoToolchain(sdk, null, 25).classpath());
    }
}
