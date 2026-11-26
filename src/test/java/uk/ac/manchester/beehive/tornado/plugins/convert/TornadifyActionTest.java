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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.testFramework.TestActionEvent;
import icons.TornadoIcons;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;

import java.util.Arrays;
import java.util.List;

public class TornadifyActionTest extends TornadoTestCase {

    private static final String HOW_TO = "Transform a method into a TornadoVM kernel with AI. "
            + "Click inside the method you want to tornadify, then click this button. "
            + "You can also right-click the method and choose Tornadify..., or click the brain icon next to its name.";

    private boolean wasEnabled;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        wasEnabled = AiSettingState.getInstance().enabled;
        AiSettingState.getInstance().enabled = true;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            AiSettingState.getInstance().enabled = wasEnabled;
        } finally {
            super.tearDown();
        }
    }

    public void testInTheToolWindowToolbarAfterTheDynamicInspectionButtons() {
        ActionManager actions = ActionManager.getInstance();
        List<String> ids = Arrays.stream(((DefaultActionGroup) actions.getAction("tornado.bar")).getChildActionsOrStubs())
                .map(actions::getId)
                .toList();
        int debug = ids.indexOf("tornado.debugDump");
        assertTrue(ids.toString(), debug >= 0);
        assertEquals(ids.toString(), "tornado.tornadify", ids.get(debug + 1));

        Presentation template = actions.getAction("tornado.tornadify").getTemplatePresentation();
        assertEquals("Tornadify", template.getText());
        assertEquals(HOW_TO, template.getDescription());
        assertSame(TornadoIcons.AiTransform, template.getIcon());
    }

    public void testEnabledForTheConvertibleMethodAtTheCaretOfTheSelectedEditorAndNamesIt() {
        addTornadoApi();
        myFixture.configureByText("Demo.java", """
                class Demo {
                    void add(int[] a, int[] b, int[] c) {
                        for (int i = 0; i < c.length; i++) {
                            c[i] = a[<caret>i] + b[i];
                        }
                    }
                }""");
        Presentation presentation = updateFromToolWindow();
        assertTrue(presentation.isEnabled());
        assertEquals("Tornadify add", presentation.getText());
        assertEquals("Transform add into a TornadoVM kernel with AI. To tornadify another method, click inside it first.",
                presentation.getDescription());
    }

    public void testDisabledWhileTheTransformationIsOff() {
        AiSettingState.getInstance().enabled = false;
        addTornadoApi();
        myFixture.configureByText("Demo.java", "class Demo { void run() { int <caret>x = 0; } }");
        assertDisabled("It is off: enable it in Settings");
    }

    public void testDisabledWithoutAnEditor() {
        addTornadoApi();
        myFixture.configureByText("Demo.java", "class Demo { void run() { int <caret>x = 0; } }");
        FileEditorManagerEx.getInstanceEx(getProject()).closeAllFiles();
        assertDisabled(HOW_TO);
    }

    public void testDisabledOutsideAMethod() {
        addTornadoApi();
        myFixture.configureByText("Demo.java", "class Demo { int <caret>x; void run() { } }");
        assertDisabled(HOW_TO);
    }

    public void testDisabledInAKernel() {
        addTornadoApi();
        myFixture.configureByText("Demo.java", """
                import uk.ac.manchester.tornado.api.annotations.Parallel;
                class Demo {
                    void kernel(int[] a) {
                        for (@Parallel int i = 0; i < a.length; i++) {
                            a[<caret>i] = 0;
                        }
                    }
                }""");
        assertDisabled(HOW_TO);
    }

    public void testDisabledWithoutTheTornadoApiSayingWhatToAdd() {
        myFixture.configureByText("Demo.java", "class Demo { void run() { int <caret>x = 0; } }");
        assertDisabled("io.github.beehive-lab:tornado-api");
    }

    private void assertDisabled(String reason) {
        Presentation presentation = updateFromToolWindow();
        assertFalse(presentation.isEnabled());
        assertEquals("Tornadify", presentation.getText());
        assertTrue(presentation.getDescription(), presentation.getDescription().startsWith("Transform a method into a TornadoVM kernel with AI. "));
        assertTrue(presentation.getDescription(), presentation.getDescription().contains(reason));
    }

    // As from the tool window's toolbar: the data context has the project, but no editor
    private Presentation updateFromToolWindow() {
        AnAction action = ActionManager.getInstance().getAction("tornado.tornadify");
        AnActionEvent event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(getProject()));
        ReadAction.run(() -> action.update(event));
        return event.getPresentation();
    }
}
