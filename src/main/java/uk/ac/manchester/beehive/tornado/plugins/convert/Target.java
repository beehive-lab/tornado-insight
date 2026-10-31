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


package uk.ac.manchester.beehive.tornado.plugins.convert;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.kernel.TornadoKernels;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The TornadoVM API a method is converted to.
 */
enum Target {

    /** {@code @Parallel} loops: the kernel keeps the method's shape, the most readable. */
    LOOP_PARALLEL("convert.target.loopParallel", "/ai/target-loop-parallel.md", TornadoKernels.Kind.PARALLEL_LOOP, null),
    /** {@code KernelContext}: explicit threads, work-groups, local memory and barriers. */
    KERNEL_API("convert.target.kernelApi", "/ai/target-kernel-api.md", TornadoKernels.Kind.KERNEL_CONTEXT,
            TornadoKernels.KERNEL_CONTEXT),
    /** {@code TileContext}: whole tiles of elements at once (CUDA Tile); TornadoVM arrays only. */
    TILE_API("convert.target.tileApi", "/ai/target-tile-api.md", TornadoKernels.Kind.TILE_CONTEXT,
            TornadoKernels.TILE_CONTEXT);

    private final String nameKey;
    private final String rules;
    private final TornadoKernels.Kind kind;
    private final @Nullable String context;

    Target(String nameKey, String rules, TornadoKernels.Kind kind, @Nullable String context) {
        this.nameKey = nameKey;
        this.rules = rules;
        this.kind = kind;
        this.context = context;
    }

    /**
     * The targets that <em>Fastest (measured)</em> converts to and times, most readable first.
     *
     * @param possible the targets the method can be converted to (see {@link #unavailable})
     * @param tiles    whether the device runs Tile API kernels
     */
    static @NotNull List<Target> compared(@NotNull Set<Target> possible, boolean tiles) {
        return Arrays.stream(values()).filter(target -> possible.contains(target) && (tiles || target != TILE_API)).toList();
    }

    /**
     * Why a method with parameters of {@code parameterTypes} cannot be converted to this target,
     * or {@code null} if it can: the project's TornadoVM API must have the target's context class
     * (an older TornadoVM has no {@code TileContext}), and the parameters must suit it (see {@link #accepts}).
     *
     * @param resolves whether a class of this qualified name is on the method's classpath
     */
    @Nullable String unavailable(@NotNull List<String> parameterTypes, @NotNull Predicate<String> resolves) {
        if (context != null && !resolves.test(context)) {
            return this == TILE_API ? MessageBundle.message("convert.target.tileApi.missingApi")
                    : MessageBundle.message("convert.target.missingApi", contextSimpleName());
        }
        return accepts(parameterTypes) ? null : MessageBundle.message("convert.target.tileApi.javaArrays");
    }

    /**
     * Whether a method with parameters of {@code parameterTypes} (canonical names) can be
     * converted to this target: tiles cannot view Java arrays, and the parameters keep their types.
     */
    boolean accepts(@NotNull List<String> parameterTypes) {
        return this != TILE_API || parameterTypes.stream().noneMatch(type -> type.endsWith("[]"));
    }

    /** Whether the kernel declares a work-group size; tile blocks have none. */
    boolean hasLocalWork() {
        return this == KERNEL_API;
    }

    /** The API's name, e.g. "Kernel API". */
    @NotNull String displayName() {
        return MessageBundle.message(nameKey);
    }

    /** The label of this target in the conversion dialog. */
    @NotNull String choiceLabel() {
        return MessageBundle.message(nameKey + ".choice");
    }

    /** What the conversion dialog says about this target. */
    @NotNull String choiceDescription() {
        return MessageBundle.message(nameKey + ".description");
    }

    /** The resource with this target's section of the rules. */
    @NotNull String rules() {
        return rules;
    }

    /** The kind of kernel this target produces. */
    @NotNull TornadoKernels.Kind kind() {
        return kind;
    }

    /**
     * The qualified name of the context parameter the kernel takes first, or {@code null} if it
     * takes none. A kernel with a context is launched on the grid it declares (see {@link KernelGrid}).
     */
    @Nullable String context() {
        return context;
    }

    /** The simple name of the context parameter, or {@code null}. */
    @Nullable String contextSimpleName() {
        return context == null ? null : context.substring(context.lastIndexOf('.') + 1);
    }
}
