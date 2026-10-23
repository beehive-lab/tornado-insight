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

import com.intellij.execution.configurations.GeneralCommandLine;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Command lines of the javac and tornado runs, against a fake SDK directory.
 */
public class CommandLinesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void javacCommandLine() {
        Path out = Path.of("/tmp/tornado-insight1/classes");
        GeneralCommandLine commandLine = JavacRunner.commandLine("/opt/jdk-25", 25,
                List.of("/sdk/tornado-api.jar", "/sdk/tornado-matrices.jar", "/sdk/tornado-unittests.jar"), out,
                List.of("/tmp/tornado-insight1/src/vaddAb12C.java", "/tmp/tornado-insight1/src/mulXy34Z.java"), false);

        assertEquals("/opt/jdk-25" + File.separator + "bin" + File.separator + "javac", commandLine.getExePath());
        assertEquals(List.of("--release", "25", "--enable-preview", "-g",
                        "-classpath", String.join(File.pathSeparator, "/sdk/tornado-api.jar", "/sdk/tornado-matrices.jar",
                                "/sdk/tornado-unittests.jar"),
                        "-d", out.toString(),
                        "/tmp/tornado-insight1/src/vaddAb12C.java", "/tmp/tornado-insight1/src/mulXy34Z.java"),
                commandLine.getParametersList().getList());
    }

    @Test
    public void javacOnWindows() {
        GeneralCommandLine commandLine = JavacRunner.commandLine("C:\\jdk-25", 25, List.of(), Path.of("out"), List.of(), true);
        assertEquals("C:\\jdk-25" + File.separator + "bin" + File.separator + "javac.exe", commandLine.getExePath());
    }

    @Test
    public void releaseFallsBackToTheMinimumSupportedJdk() {
        assertEquals(25, JavacRunner.release(25));
        assertEquals(21, JavacRunner.release(-1));
    }

    @Test
    public void missingJarsAreNamed() {
        assertEquals("", JavacRunner.missingJars("a", "b", "c"));
        assertEquals("tornado-api.jar tornado-unittests.jar ", JavacRunner.missingJars(null, "b", null));
    }

    @Test
    public void tornadoCommandLines() throws IOException {
        Path home = temp.newFolder("tornadovm").toPath();
        Files.createDirectories(home.resolve("bin"));
        Files.createFile(home.resolve("bin/tornado"));
        Path runDir = temp.newFolder("run").toPath();
        TornadoRunner runner = new TornadoRunner(TornadoSdk.forHome(home.toString(), false), "/opt/jdk-25", runDir);

        GeneralCommandLine run = runner.runCommand(Path.of("/tmp/jars/vaddAb12C.jar"));
        assertEquals(home.resolve("bin/tornado").toString(), run.getExePath());
        assertEquals(List.of("--printKernel", "-jar", Path.of("/tmp/jars/vaddAb12C.jar").toString()),
                run.getParametersList().getList());
        assertEquals(runDir.toFile(), run.getWorkDirectory());
        assertEquals("/opt/jdk-25", run.getEnvironment().get("JAVA_HOME"));
        assertEquals(home.toString(), run.getEnvironment().get("TORNADOVM_HOME"));

        GeneralCommandLine devices = runner.deviceCheckCommand();
        assertEquals(List.of("--device"), devices.getParametersList().getList());
        assertEquals(runDir.toFile(), devices.getWorkDirectory());
    }

    @Test
    public void timeoutsAreDescribed() {
        assertEquals("2 minutes", Processes.describeTimeout(JavacRunner.TIMEOUT_MS));
        assertEquals("5 minutes", Processes.describeTimeout(TornadoRunner.RUN_TIMEOUT_MS));
        assertEquals("60 seconds", Processes.describeTimeout(TornadoRunner.PROBE_TIMEOUT_MS));
    }
}
