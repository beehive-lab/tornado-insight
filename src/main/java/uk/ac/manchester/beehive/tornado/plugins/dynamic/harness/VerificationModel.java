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

import java.util.List;

/**
 * What a verification harness needs: the candidate kernel and the original method, each copied
 * with the members they use, and how the kernel is launched.
 *
 * @param kernel             the kernel, copied as the harness class
 * @param kernelParameters   the kernel's parameters, in order
 * @param original           the original method, copied as a nested class of the harness
 * @param originalParameters the original method's parameters, in order
 * @param originalStatic     whether the original method is static
 * @param originalReturnType the canonical return type of the original method, {@code void} if none
 * @param grid               for a Kernel API or Tile API kernel, the global work size per dimension
 *                           as Java expressions over the kernel's parameters; empty for a
 *                           {@code @Parallel} kernel
 * @param localWork          the local work size per dimension, or empty to leave it to TornadoVM
 */
public record VerificationModel(@NotNull HarnessModel kernel, @NotNull List<Parameter> kernelParameters,
                                @NotNull HarnessModel original, @NotNull List<Parameter> originalParameters,
                                boolean originalStatic, @NotNull String originalReturnType,
                                @NotNull List<String> grid, @NotNull List<String> localWork) {

    public VerificationModel {
        kernelParameters = List.copyOf(kernelParameters);
        originalParameters = List.copyOf(originalParameters);
        grid = List.copyOf(grid);
        localWork = List.copyOf(localWork);
    }

    /**
     * A method parameter.
     *
     * @param name the parameter name
     * @param type the canonical type
     */
    public record Parameter(@NotNull String name, @NotNull String type) {
    }
}
