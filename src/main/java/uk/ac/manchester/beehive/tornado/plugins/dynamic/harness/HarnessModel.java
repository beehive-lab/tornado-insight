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

package uk.ac.manchester.beehive.tornado.plugins.dynamic.harness;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;

import java.util.List;

/**
 * Everything needed to generate the test harness of one kernel, as plain data.
 * Built from the PSI by {@link HarnessModelBuilder} and turned into Java source by
 * {@link HarnessGenerator}.
 *
 * @param className     the name of the generated public class
 * @param kernelName    the name of the kernel method
 * @param kind          how the kernel expresses its parallelism, or {@code null} if unknown
 * @param imports       import statements to add after the default ones, one per element
 * @param fields        the fields to copy into the harness, in emission order
 * @param kernelSource  the source text of the kernel method
 * @param helperSources the source text of the helper methods the kernel calls
 * @param parameters    the harness variables passed to the kernel, in argument order
 * @param taskGraph     the user's TaskGraph declaration to reuse, or {@code null} to
 *                      build a single-task graph for the kernel
 */
public record HarnessModel(@NotNull String className,
                           @NotNull String kernelName,
                           @Nullable TornadoKernels.Kind kind,
                           @NotNull List<String> imports,
                           @NotNull List<Field> fields,
                           @NotNull String kernelSource,
                           @NotNull List<String> helperSources,
                           @NotNull List<Parameter> parameters,
                           @Nullable TaskGraphDeclaration taskGraph) {

    public HarnessModel {
        imports = List.copyOf(imports);
        fields = List.copyOf(fields);
        helperSources = List.copyOf(helperSources);
        parameters = List.copyOf(parameters);
    }

    /**
     * A field copied into the harness.
     *
     * @param modifiers   the modifiers, space separated (may be empty)
     * @param type        the field type
     * @param name        the field name
     * @param initializer the initializer text, or {@code null}
     */
    public record Field(@NotNull String modifiers, @NotNull String type, @NotNull String name,
                        @Nullable String initializer) {

        public boolean isStatic() {
            return (" " + modifiers + " ").contains(" static ");
        }
    }

    /**
     * A variable the harness declares, initialises with test data and passes to the kernel.
     *
     * @param name            the variable name
     * @param type            the variable type
     * @param localMemorySize whether the value sizes a local-memory allocation, so it
     *                        must stay small
     * @param intOverride     for an {@code int}, the name of a field to initialise it
     *                        with instead of the configured size, or {@code null}
     */
    public record Parameter(@NotNull String name, @NotNull String type, boolean localMemorySize,
                            @Nullable String intOverride) {
    }

    /**
     * A TaskGraph declaration from the user's code, rewritten to call the kernel
     * copied into the harness.
     *
     * @param declaration  the declaration statement
     * @param variableName the name of the TaskGraph variable it declares
     * @param graphName    the TaskGraph name, if it is a string literal
     * @param taskName     the name of the kernel's task, if it is a string literal
     */
    public record TaskGraphDeclaration(@NotNull String declaration, @NotNull String variableName,
                                       @Nullable String graphName, @Nullable String taskName) {
    }
}
