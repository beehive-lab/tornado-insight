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
 * User Interface (§3.3 of the MPLR '25 paper, https://doi.org/10.1145/3759426.3760984).
 * <p>
 * {@code ui.toolwindow} holds the TornadoVM tool window ({@code TornadoSideWindow}): the list
 * of tasks to select for dynamic inspection and the Inspector tab ({@code InspectorInfo})
 * explaining each TornadoVM restriction, plus {@code DataKeys} that hand the selected tasks to
 * the toolbar actions. {@code ui.console} holds the TornadoInsight console tool window
 * ({@code ConsoleWindowFactory}, {@code ConsolePanel}) that shows dynamic inspection output. The
 * settings page lives in {@code settings}.
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.ui;
