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
 * AI-assisted conversion of a plain Java method to a TornadoVM Loop Parallel kernel.
 * <p>
 * {@link uk.ac.manchester.beehive.tornado.plugins.convert.ConvertToKernelAction} and
 * {@link uk.ac.manchester.beehive.tornado.plugins.convert.ConvertToKernelIntention} start
 * {@link uk.ac.manchester.beehive.tornado.plugins.convert.ConversionLauncher}, which runs
 * {@code Converter} in the background: the model gets the rules in {@code /ai/tornadovm-rules.md}
 * and the method; each answer takes the method's place in a copy of the file ({@code KernelReplacement}),
 * a misplaced {@code @Parallel} is moved mechanically
 * ({@link uk.ac.manchester.beehive.tornado.plugins.kernel.ParallelPlacement}), and
 * {@code CandidateChecker} sends what is still wrong back to the model, for up to three answers.
 * {@code ConversionPreviewDialog} shows the diff; Accept replaces the method with the kernel.
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.convert;
