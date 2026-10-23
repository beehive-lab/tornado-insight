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

package uk.ac.manchester.beehive.tornado.plugins.inspector;

import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Guards the inspection registrations in plugin.xml: user inspection profiles are
 * keyed by the short names, so they must not change.
 */
public class InspectionRegistrationTest extends BasePlatformTestCase {

    public void testInspectionsAreRegisteredUnderTheirShortNamesAndLevels() {
        Map<String, String> levels = new TreeMap<>();
        for (LocalInspectionEP ep : ours()) {
            levels.put(ep.getShortName(), ep.level);
        }
        assertEquals(Map.of(
                "AssertInspection", "ERROR",
                "DataTypeInspection", "ERROR",
                "ExternalInspection", "WARNING",
                "NonVoidReturnInspection", "WARNING",
                "RecursionInspection", "ERROR",
                "StaticTaskGraphInspection", "ERROR",
                "SystemCallInspection", "ERROR",
                "ThrowInspection", "ERROR",
                "UnanalysableHelperInspection", "WEAK WARNING"), levels);
    }

    public void testDisplayNamesAndGroupComeFromTheBundle() {
        for (LocalInspectionEP ep : ours()) {
            assertEquals("TornadoVM", ep.getGroupDisplayName());
            assertFalse(ep.getShortName(), ep.getDisplayName() == null || ep.getDisplayName().isBlank());
        }
        LocalInspectionEP recursion = ours().stream()
                .filter(ep -> ep.getShortName().equals("RecursionInspection")).findFirst().orElseThrow();
        assertEquals("Recursion check", recursion.getDisplayName());
    }

    private static List<LocalInspectionEP> ours() {
        return LocalInspectionEP.LOCAL_INSPECTION.getExtensionList().stream()
                .filter(ep -> ep.implementationClass.startsWith("uk.ac.manchester.beehive.tornado.plugins."))
                .toList();
    }
}
