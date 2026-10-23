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
 * https://doi.org/10.1145/3759426.3760984): runs the selected tasks on TornadoVM and reports
 * how they compile and execute.
 * <p>
 * {@link DynamicInspectionPipeline} checks the project JDK and the SDK, then runs five stages
 * per task:
 * <ol>
 * <li>generate a test harness: {@link CodeGenerator} (with {@link LocalMemoryParameterAnalyzer}
 * and {@code dynamic.harness});</li>
 * <li>compile it with {@code javac}: {@code dynamic.run.JavacRunner};</li>
 * <li>package it as a jar: {@code dynamic.run.JarPacker};</li>
 * <li>run it with TornadoVM: {@code dynamic.run.TornadoRunner};</li>
 * <li>analyse the output: {@link CompilerOutputSummarizer}, {@link RuntimeOutputClassifier} and
 * {@link DynamicInspectionReporter}.</li>
 * </ol>
 */
package uk.ac.manchester.beehive.tornado.plugins.dynamic;
