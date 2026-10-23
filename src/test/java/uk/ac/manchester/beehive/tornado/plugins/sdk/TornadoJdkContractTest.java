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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TornadoJdkContractTest {

    @Test
    public void contractFileWithFloor() {
        // etc/tornado.jdk of the TornadoVM 7.3.1 SDK
        TornadoJdkContract contract = TornadoJdkContract.parseContractFile(
                "tornado.jdk.floor=22\ntornado.jdk.preview=false\n");
        assertEquals(new TornadoJdkContract(22, false, true), contract);
        assertFalse(contract.accepts(21));
        assertTrue(contract.accepts(22));
        assertTrue(contract.accepts(25));
        assertEquals("JDK 22 or newer", contract.requirement());
        assertEquals("etc/tornado.jdk", contract.source());
    }

    @Test
    public void previewBuildIsPinnedToItsFloor() {
        TornadoJdkContract contract = TornadoJdkContract.parseContractFile(
                "# written by the SDK build\ntornado.jdk.floor = 25\ntornado.jdk.preview = true\n");
        assertEquals(new TornadoJdkContract(25, true, true), contract);
        assertTrue(contract.accepts(25));
        assertFalse(contract.accepts(26));
        assertEquals("JDK 25 exactly (this build has preview features enabled)", contract.requirement());
    }

    @Test
    public void contractFileWithoutUsableFloor() {
        assertNull(TornadoJdkContract.parseContractFile(""));
        assertNull(TornadoJdkContract.parseContractFile("tornado.jdk.preview=false\n"));
        assertNull(TornadoJdkContract.parseContractFile("tornado.jdk.floor=twenty-one\n"));
    }

    @Test
    public void currentVersionOutputHasNoJdkMarker() {
        // 'tornado --version' of TornadoVM 7.3.1: the JDK is in etc/tornado.jdk instead
        String output = "version=7.3.1\nbranch=UNKNOWN\ncommit=d01c104\n\nBackends installed: \n\t - cuda\n";
        assertNull(TornadoJdkContract.parseVersionOutput(output));
    }

    @Test
    public void legacyVersionOutputPinsTheJdk() {
        String output = "version=1.0.4-jdk21\nbranch=master\ncommit=a1b2c3d\n\nBackends installed: \n\t - opencl\n";
        TornadoJdkContract contract = TornadoJdkContract.parseVersionOutput(output);
        assertEquals(new TornadoJdkContract(21, true, false), contract);
        assertEquals("tornado --version", contract.source());
    }

    @Test
    public void rejectedJdkNamesTheRequiredOne() {
        assertEquals(new TornadoJdkContract(25, true, false), TornadoJdkContract.parseVersionOutput(
                "\n[ERROR] TornadoVM is only compatible with JDK version 25\n"));
        assertEquals(new TornadoJdkContract(21, true, false), TornadoJdkContract.parseVersionOutput(
                "TornadoVM supports only JDK version 21"));
    }

    @Test
    public void graalVmReleaseFiles() {
        assertTrue(TornadoJdkContract.isGraalVmRelease(List.of(
                "IMPLEMENTOR=\"GraalVM Community\"", "GRAALVM_VERSION=\"23.1.2\"")));
        assertTrue(TornadoJdkContract.isGraalVmRelease(List.of(
                "JAVA_RUNTIME_VERSION=\"21.0.2+13-jvmci-23.1-b30\"")));
        assertFalse(TornadoJdkContract.isGraalVmRelease(List.of(
                "IMPLEMENTOR=\"Eclipse Adoptium\"", "JAVA_RUNTIME_VERSION=\"25.0.2+10-LTS\"")));
    }
}
