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

package uk.ac.manchester.beehive.tornado.plugins.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class TornadoSettingsComponentTest extends BasePlatformTestCase {

    private static final int MAX = TornadoSettingState.MAX_PARAMETER_SIZE;

    public void testGetMaxArraySizeNeverThrows() {
        TornadoSettingsComponent component = new TornadoSettingsComponent();

        component.setMaxArraySizeText("12a");
        assertEquals(TornadoSettingState.DEFAULT_PARAMETER_SIZE, component.getMaxArraySize());

        component.setMaxArraySizeText("");
        assertEquals(TornadoSettingState.DEFAULT_PARAMETER_SIZE, component.getMaxArraySize());

        component.setMaxArraySizeText("99999999999");
        assertEquals(TornadoSettingState.DEFAULT_PARAMETER_SIZE, component.getMaxArraySize());

        component.setMaxArraySizeText("256");
        assertEquals(256, component.getMaxArraySize());
    }

    public void testValidatorUsesSharedUpperBound() {
        TornadoSettingsComponent component = new TornadoSettingsComponent();

        component.setMaxArraySizeText(String.valueOf(MAX - 1));
        assertEquals("", component.isValidPath());

        component.setMaxArraySizeText("16384");
        assertEquals("", component.isValidPath());

        component.setMaxArraySizeText(String.valueOf(MAX));
        String error = component.isValidPath();
        assertFalse(error.isEmpty());
        assertTrue(error, error.contains(String.valueOf(MAX)));

        component.setMaxArraySizeText("12a");
        assertFalse(component.isValidPath().isEmpty());
    }
}
