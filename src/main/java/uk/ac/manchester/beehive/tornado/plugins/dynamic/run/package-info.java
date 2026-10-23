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
 * Dynamic Inspection Module (§3.2 of the MPLR '25 paper,
 * https://doi.org/10.1145/3759426.3760984), stages 2 to 4: the external processes.
 * <p>
 * {@link JavacRunner} compiles the harnesses, {@link JarPacker} packs each into a jar and
 * {@link TornadoRunner} runs it with the {@code tornado} launcher; {@link Processes} runs the
 * child processes.
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.dynamic.run;
