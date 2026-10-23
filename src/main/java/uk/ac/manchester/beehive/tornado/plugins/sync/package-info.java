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
 * Communication and Data Synchronization (§3.4 of the MPLR '25 paper,
 * https://doi.org/10.1145/3759426.3760984).
 * <p>
 * Event listeners (§3.4.1): {@link TornadoSettingListener} is the configuration listener: on
 * project open it warns when {@code TORNADOVM_HOME} is not set; {@link ToolWindowOpen} and
 * {@link EditorSwitch} react to the tool window being shown and to editor switches. They
 * publish on the message-bus topic {@link TornadoTaskRefreshListener}, to which the tool window
 * subscribes, together with the live-code-edit listener (PSI modification).
 * </p>
 * <p>
 * Data synchronization (§3.4.2): {@link TornadoTaskService} keeps, per project, an immutable
 * snapshot of the current file's tasks that are syntactically valid and free of blocking static
 * problems; the tool window lists them and dynamic inspection takes its tasks from them. Unlike
 * the paper, where the inspections add methods to a shared CopyOnWriteArraySet, the service
 * re-runs the same static rules through {@code inspection.KernelValidator}: the shared set was
 * global across projects, keyed by method text and dependent on the order in which the
 * inspections ran.
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.sync;
