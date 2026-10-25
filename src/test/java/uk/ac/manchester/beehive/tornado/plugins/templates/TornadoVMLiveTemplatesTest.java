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

package uk.ac.manchester.beehive.tornado.plugins.templates;

import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.testFramework.ServiceContainerUtil;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.sdk.TornadoSdk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TornadoVMLiveTemplatesTest extends TornadoTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TemplateManagerImpl.setTemplateTesting(getTestRootDisposable());
        // Independent of the TORNADOVM_HOME of the machine running the tests
        useSdk(null);
    }

    public void testTaskGraph() {
        addTornadoApi();
        expand("class Demo { void run() { tg<caret> } }");
        assertExpanded("""
                import uk.ac.manchester.tornado.api.TaskGraph;
                import uk.ac.manchester.tornado.api.enums.DataTransferMode;

                class Demo { void run() {
                    TaskGraph taskGraph = new TaskGraph("s0")
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, )
                            .task("t0", Demo::, )
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, );
                } }""");
    }

    public void testExecutionPlan() {
        addTornadoApi();
        expand("""
                import uk.ac.manchester.tornado.api.TaskGraph;
                class Demo { void run(TaskGraph graph) { tep<caret> } }""");
        assertExpanded("""
                import uk.ac.manchester.tornado.api.TaskGraph;
                import uk.ac.manchester.tornado.api.TornadoExecutionPlan;

                class Demo { void run(TaskGraph graph) {
                    try (TornadoExecutionPlan executionPlan = new TornadoExecutionPlan(graph.snapshot())) {
                        executionPlan.execute();
                    }
                } }""");
    }

    public void testParallelKernel() {
        addTornadoApi();
        expand("class Demo { kpar<caret> }");
        assertExpanded("""
                import uk.ac.manchester.tornado.api.annotations.Parallel;

                class Demo {
                    public static void compute() {
                        for (@Parallel int i = 0; i < ; i++) {

                        }
                    }
                }""");
    }

    public void testKernelContextKernel() {
        addTornadoApi();
        expand("class Demo { kctx<caret> }");
        assertExpanded("""
                import uk.ac.manchester.tornado.api.KernelContext;

                class Demo {
                    public static void compute(KernelContext context, ) {

                    }
                }""");
    }

    public void testTileContextKernel() {
        addTornadoApi();
        addTileContext();
        expand("class Demo { ktile<caret> }");
        assertExpanded("""
                import uk.ac.manchester.tornado.api.tile.TileContext;

                class Demo {
                    public static void compute(TileContext tc, ) {

                    }
                }""");
    }

    public void testNotOfferedWithoutTornadoVM() {
        expand("class Demo { void run() { tg<caret> } }");
        assertFalse(myFixture.getEditor().getDocument().getText().contains("TaskGraph"));
    }

    public void testTileTemplateNotOfferedWithoutTileContext() {
        addTornadoApi();
        expand("class Demo { ktile<caret> }");
        assertFalse(myFixture.getEditor().getDocument().getText().contains("TileContext"));
    }

    /** A project that compiles against the SDK's modules: the API does not resolve, but TORNADOVM_HOME has it. */
    public void testOfferedWithTheSdkOfTornadoVmHome() throws IOException {
        useSdk(sdkWithApiJar("uk.ac.manchester.tornado.api.TaskGraph"));
        expand("class Demo { void run() { tg<caret> } }");
        assertTrue(myFixture.getEditor().getDocument().getText().contains("TaskGraph"));
    }

    public void testTileTemplateOfferedWhenTheSdkHasTileContext() throws IOException {
        useSdk(sdkWithApiJar("uk.ac.manchester.tornado.api.TaskGraph", "uk.ac.manchester.tornado.api.tile.TileContext"));
        expand("class Demo { ktile<caret> }");
        assertTrue(myFixture.getEditor().getDocument().getText().contains("TileContext"));
    }

    public void testTileTemplateNotOfferedWhenTheSdkLacksTileContext() throws IOException {
        useSdk(sdkWithApiJar("uk.ac.manchester.tornado.api.TaskGraph"));
        expand("class Demo { ktile<caret> }");
        assertFalse(myFixture.getEditor().getDocument().getText().contains("TileContext"));
    }

    private void useSdk(Path home) {
        TornadoSdk sdk = TornadoSdk.forHome(home == null ? null : home.toString(), false);
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), TornadoSdk.class, sdk, getTestRootDisposable());
    }

    /** An SDK home whose share/java/tornado/tornado-api jar has an (empty) class file per name. */
    private static Path sdkWithApiJar(String... classNames) throws IOException {
        Path home = Files.createTempDirectory("tornado-sdk");
        Path jars = Files.createDirectories(home.resolve("share/java/tornado"));
        try (OutputStream out = Files.newOutputStream(jars.resolve("tornado-api-7.3.1.jar")); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String className : classNames) {
                zip.putNextEntry(new ZipEntry(className.replace('.', '/') + ".class"));
                zip.closeEntry();
            }
        }
        return home;
    }

    /** Compares the editor text with the expected code, ignoring formatting, which follows the user's code style. */
    private void assertExpanded(String expected) {
        String actual = myFixture.getEditor().getDocument().getText();
        assertEquals(normalize(expected), normalize(actual));
    }

    private static String normalize(String code) {
        return code.replaceAll("\\s+", " ").replaceAll(" ?([(){};,<]) ?", "$1").trim();
    }

    /** Expands the template before the caret and accepts the default of every variable. */
    private void expand(String text) {
        myFixture.configureByText("Demo.java", text);
        myFixture.type('\t');
        TemplateState state = TemplateManagerImpl.getTemplateState(myFixture.getEditor());
        while (state != null && !state.isFinished()) {
            TemplateState current = state;
            WriteCommandAction.runWriteCommandAction(getProject(), current::nextTab);
        }
    }
}
