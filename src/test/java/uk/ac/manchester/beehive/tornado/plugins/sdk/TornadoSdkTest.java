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

import com.intellij.execution.configurations.GeneralCommandLine;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TornadoSdk against a fake SDK directory laid out like TornadoVM 7.3.1.
 */
public class TornadoSdkTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path home;

    @Before
    public void createFakeSdk() throws IOException {
        home = temp.newFolder("tornadovm-7.3.1").toPath();
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/tornado"));
        Files.createFile(home.resolve("bin/tornado.exe"));
        Path jars = Files.createDirectories(home.resolve("share/java/tornado"));
        for (String jar : List.of("tornado-api-7.3.1.jar", "tornado-matrices-7.3.1.jar", "tornado-unittests-7.3.1.jar",
                "tornado-annotation-7.3.1.jar", "tornado-api-7.3.1-sources.txt")) {
            Files.createFile(jars.resolve(jar));
        }
        Files.createDirectories(home.resolve("etc"));
        Files.writeString(home.resolve("etc/tornado.jdk"), "tornado.jdk.floor=22\ntornado.jdk.preview=false\n");
    }

    @Test
    public void unsetHomeIsNotConfigured() {
        for (String value : new String[]{null, ""}) {
            TornadoSdk sdk = TornadoSdk.forHome(value, false);
            assertFalse(sdk.isConfigured());
            assertNull(sdk.homeValue());
            assertNull(sdk.home());
            assertEquals("tornado", sdk.launcherPath());
            assertNull(sdk.apiJar());
            assertNull(sdk.contractFromFile());
        }
    }

    @Test
    public void launcherIsResolvedUnderBin() {
        assertEquals(home.resolve("bin/tornado").toString(), TornadoSdk.forHome(home.toString(), false).launcherPath());
        assertEquals(home.resolve("bin/tornado.exe").toString(), TornadoSdk.forHome(home.toString(), true).launcherPath());
    }

    @Test
    public void missingLauncherFallsBackToPathLookup() throws IOException {
        Files.delete(home.resolve("bin/tornado"));
        assertEquals("tornado", TornadoSdk.forHome(home.toString(), false).launcherPath());
    }

    @Test
    public void jarsAreFoundByPrefix() {
        TornadoSdk sdk = TornadoSdk.forHome(home.toString(), false);
        Path jars = home.resolve("share/java/tornado");
        assertEquals(jars.resolve("tornado-api-7.3.1.jar").toString(), sdk.apiJar());
        assertEquals(jars.resolve("tornado-matrices-7.3.1.jar").toString(), sdk.matricesJar());
        assertEquals(jars.resolve("tornado-unittests-7.3.1.jar").toString(), sdk.unitTestsJar());
        assertNull(sdk.findJar("tornado-cublas"));
    }

    @Test
    public void jarsAreEveryJarOfTheSdk() {
        Path jars = home.resolve("share/java/tornado");
        assertEquals(Stream.of("tornado-annotation-7.3.1.jar", "tornado-api-7.3.1.jar", "tornado-matrices-7.3.1.jar",
                        "tornado-unittests-7.3.1.jar").map(jar -> jars.resolve(jar).toString()).toList(),
                TornadoSdk.forHome(home.toString(), false).jars());
        assertEquals(List.of(), TornadoSdk.forHome(null, false).jars());
    }

    @Test
    public void relativeHomeIsResolvedAgainstTheUserHome() {
        TornadoSdk sdk = TornadoSdk.forHome("sdks/tornadovm", false);
        assertTrue(sdk.isConfigured());
        assertEquals("sdks/tornadovm", sdk.homeValue());
        assertEquals(Path.of(System.getProperty("user.home"), "sdks/tornadovm"), sdk.home());
    }

    @Test
    public void contractIsReadFromEtcTornadoJdk() throws IOException {
        TornadoSdk sdk = TornadoSdk.forHome(home.toString(), false);
        assertEquals(new TornadoJdkContract(22, false, true), sdk.contractFromFile());

        Files.writeString(home.resolve("etc/tornado.jdk"), "tornado.jdk.preview=true\n");
        assertNull(sdk.contractFromFile());
        Files.delete(home.resolve("etc/tornado.jdk"));
        assertNull(sdk.contractFromFile());
    }

    @Test
    public void childEnvironmentPointsAtTheSdk() {
        TornadoSdk sdk = TornadoSdk.forHome(home.toString(), false);
        GeneralCommandLine commandLine = sdk.launcherCommand("/opt/jdk-25", "--version");

        assertEquals(home.resolve("bin/tornado").toString(), commandLine.getExePath());
        assertEquals(List.of("--version"), commandLine.getParametersList().getList());
        assertEquals(GeneralCommandLine.ParentEnvironmentType.CONSOLE, commandLine.getParentEnvironmentType());
        Map<String, String> env = commandLine.getEnvironment();
        assertEquals("/opt/jdk-25", env.get("JAVA_HOME"));
        assertEquals(home.toString(), env.get("TORNADOVM_HOME"));
        assertTrue(env.get("PATH"), env.get("PATH").startsWith(home + File.separator + "bin" + File.pathSeparator));
    }

    @Test
    public void unknownJavaHomeIsNotSet() {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        TornadoSdk.forHome(home.toString(), false).configureEnvironment(commandLine, null);
        assertFalse(commandLine.getEnvironment().containsKey("JAVA_HOME"));
    }
}
