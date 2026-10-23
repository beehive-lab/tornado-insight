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

/**
 * TornadoInsight, an IntelliJ Platform plugin for TornadoVM, organised after the four modules
 * described in Stratikopoulos et al., "Dynamic and Static Code Analysis for Java Programs on
 * Heterogeneous Hardware", MPLR '25, https://doi.org/10.1145/3759426.3760984:
 * <ul>
 * <li>Static Inspection Module (§3.1): {@code kernel} and {@code inspection};</li>
 * <li>Dynamic Inspection Module (§3.2): {@code dynamic}, with {@code dynamic.harness} and
 * {@code dynamic.run};</li>
 * <li>User Interface (§3.3): {@code ui.toolwindow}, {@code ui.console} and
 * {@code settings};</li>
 * <li>Communication and Data Synchronization (§3.4): {@code sync}.</li>
 * </ul>
 * Supporting packages: {@code sdk} (locating the TornadoVM SDK and its JDK contract),
 * {@code actions} (the tool window toolbar actions), {@code templates} (live template contexts),
 * {@code error} (the error report submitter) and {@code util} (message bundle, console service,
 * notifications).
 */
package uk.ac.manchester.beehive.tornado.plugins;
