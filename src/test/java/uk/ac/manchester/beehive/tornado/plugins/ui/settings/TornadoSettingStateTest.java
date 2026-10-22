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

package uk.ac.manchester.beehive.tornado.plugins.ui.settings;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TornadoSettingStateTest {

    @Test
    public void settingsSavedByEarlierVersionsStillLoad() throws Exception {
        // Earlier versions also persisted the derived 'isValid' flag
        Element saved = JDOMUtil.load("""
                <TornadoVMSettingsState>
                  <option name="debugFileSaveLocation" value="/home/user/tornado-debug" />
                  <option name="isValid" value="true" />
                  <option name="parameterSize" value="256" />
                  <option name="saveFileEnabled" value="true" />
                </TornadoVMSettingsState>""");

        TornadoSettingState state = XmlSerializer.deserialize(saved, TornadoSettingState.class);

        assertEquals(256, state.parameterSize);
        assertTrue(state.saveFileEnabled);
        assertEquals("/home/user/tornado-debug", state.debugFileSaveLocation);
    }

    @Test
    public void onlyTheUserSettingsArePersisted() {
        TornadoSettingState state = new TornadoSettingState();
        state.parameterSize = 512;
        state.debugFileSaveLocation = "/tmp/debug";

        String xml = JDOMUtil.writeElement(XmlSerializer.serialize(state));

        assertTrue(xml, xml.contains("<option name=\"parameterSize\" value=\"512\" />"));
        assertTrue(xml, xml.contains("<option name=\"debugFileSaveLocation\" value=\"/tmp/debug\" />"));
        assertFalse(xml, xml.contains("isValid"));
    }
}
