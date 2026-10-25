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
 * Static Inspection Module (§3.1 of the MPLR '25 paper,
 * https://doi.org/10.1145/3759426.3760984), the analysis core: what a kernel is and what it
 * reaches, independent of the inspection framework.
 * <p>
 * {@link TornadoKernels} defines a kernel, {@link KernelCallGraphAnalyzer} finds the helpers
 * TornadoVM would inline, {@link SupportedKernelTypes} reads the supported data types from
 * {@code tornado/supported-kernel-types.txt} (§3.1.1), {@link RestrictedClasses} lists the JDK
 * classes and packages kernels may not call, and {@link TornadoTWTask} finds and formats the
 * kernels of a file. Findings go to a {@link KernelProblemSink}. This package has no code
 * dependency on any other plugin package.
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.kernel;
