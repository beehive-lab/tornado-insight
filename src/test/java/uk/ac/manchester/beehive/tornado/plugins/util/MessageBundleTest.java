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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class MessageBundleTest extends BasePlatformTestCase {

    public void testMessageWithoutParametersIsReturnedAsWritten() {
        assertTrue(MessageBundle.message("inspection.assert").startsWith("TornadoVM: 'assert' is not supported"));
    }

    public void testParametersAreSubstituted() {
        assertEquals("Parameter scale needs to be greater than 0 and less than 65536",
                MessageBundle.message("ui.settings.validation.invalidSize", "65536"));
    }

    public void testQuotedMessageWithParameters() {
        assertEquals("Set 'Max array size' in TornadoInsight settings to a value between 1 and 65535 (recommended: 128). "
                        + "Current value is 0, which would generate empty inputs and produce a misleading green run.",
                MessageBundle.message("dynamic.error.parameterSize.content", "65535",
                        MessageBundle.message("dynamic.error.parameterSize.empty", "0")));
    }

    public void testMultiLineAndSpacedMessages() {
        assertEquals("TornadoVM is not properly installed or configured.\n\nError: boom",
                MessageBundle.message("dynamic.error.tornadoLaunch", "boom"));
        assertTrue(MessageBundle.message("notification.sdkNotConfigured.content")
                .startsWith("<html>TORNADOVM_HOME environment variable is not set.<br><br>Please:<br>1. Download"));
    }

    public void testActionTextsComeFromTheBundle() {
        ActionManager actions = ActionManager.getInstance();
        assertEquals("Refresh", actions.getAction("tornado.refresh").getTemplatePresentation().getText());
        assertEquals("Run Dynamic Inspection", actions.getAction("tornado.runInspection").getTemplatePresentation().getText());
        assertEquals("Run dynamic inspection",
                actions.getAction("tornado.runInspection").getTemplatePresentation().getDescription());
        assertEquals("Settings", actions.getAction("tornado.settings").getTemplatePresentation().getText());
        assertEquals("TornadoVM Documentation", actions.getAction("tornado.doc").getTemplatePresentation().getText());
    }
}
