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
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider;
import com.intellij.openapi.actionSystem.ex.TooltipLinkProvider;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.testFramework.TestActionEvent;
import icons.TornadoIcons;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;

public class TornadifyActionTest extends TornadoTestCase {

    private static final String HOW_TO = "Click inside a method, then click here to turn it into a TornadoVM kernel with AI. "
            + "Tip: the brain icon next to a method's name works too.";

    private static final String NO_API = "This module needs tornado-api. Add io.github.beehive-lab:tornado-api:8.1.0-jdk21 "
            + "(JDK 21) or :8.1.0-jdk22plus (JDK 22+) to your pom.xml, then reload Maven.";

    private static final String OFF = "AI-assisted transformation is off. Turn it on to tornadify your methods.";

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

        // Without it, the toolbar's tooltip shows the title alone, not the description
        assertTrue(actions.getAction("tornado.tornadify") instanceof TooltipDescriptionProvider);

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
        assertEquals("Turn add into a TornadoVM kernel with AI. For another method, click inside it first.",
                presentation.getDescription());
    }

    public void testDisabledWhileTheTransformationIsOff() {
        AiSettingState.getInstance().enabled = false;
        addTornadoApi();
        myFixture.configureByText("Demo.java", "class Demo { void run() { int <caret>x = 0; } }");
        assertDisabled(OFF);
    }

    public void testTooltipLinksToTheSettingsOnlyWhileTheTransformationIsOff() {
        TooltipLinkProvider action = (TooltipLinkProvider) ActionManager.getInstance().getAction("tornado.tornadify");
        assertNull(action.getTooltipLink(new JLabel()));
        AiSettingState.getInstance().enabled = false;
        assertEquals("Open settings", action.getTooltipLink(new JLabel()).tooltip);
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
        assertDisabled(NO_API);
    }

    public void testSaysWhatToAddWithoutTheTornadoApiWhereverTheCaretIs() {
        // Outside a method the missing dependency still comes first, not the how-to
        myFixture.configureByText("Demo.java", "class Demo { int <caret>x; }");
        assertDisabled(NO_API);
    }

    public void testSaysItIsOffBeforeTheMissingTornadoApi() {
        AiSettingState.getInstance().enabled = false;
        myFixture.configureByText("Demo.java", "class Demo { void run() { int <caret>x = 0; } }");
        assertDisabled(OFF);
    }

    private void assertDisabled(String reason) {
        Presentation presentation = updateFromToolWindow();
        assertFalse(presentation.isEnabled());
        assertEquals("Tornadify", presentation.getText());
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
