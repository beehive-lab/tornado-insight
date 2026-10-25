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
 * User Interface (§3.3 of the MPLR '25 paper, https://doi.org/10.1145/3759426.3760984): the
 * TornadoInsight settings page.
 * <p>
 * {@link TornadoSettingConfiguration} is the configurable, {@link TornadoSettingsComponent} its
 * form, and {@link TornadoSettingState} the persisted application-level settings (the array
 * size of the generated test data, and where to save the generated harnesses).
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.settings;
