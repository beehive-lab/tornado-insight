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

package uk.ac.manchester.beehive.tornado.plugins.kernel;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single definition of what a TornadoVM kernel is, shared by the inspections,
 * the task panel and dynamic inspection.
 * <p>
 * A method is a kernel when it declares a {@code KernelContext} or {@code TileContext}
 * parameter, or when it contains a {@code @Parallel}/{@code @Reduce} annotation (on a
 * loop variable or a parameter) whose closest enclosing method is that method.
 * TornadoVM types are matched by fully qualified name when the reference resolves;
 * when it does not (e.g. the TornadoVM API is missing from the classpath), the simple
 * name is matched instead, so such projects still get their kernels recognised.
 * </p>
 */
public final class TornadoKernels {

    /** The package prefix of the TornadoVM API. */
    public static final String TORNADO_API = "uk.ac.manchester.tornado.api.";
    public static final String PARALLEL = "uk.ac.manchester.tornado.api.annotations.Parallel";
    public static final String REDUCE = "uk.ac.manchester.tornado.api.annotations.Reduce";
    public static final String KERNEL_CONTEXT = "uk.ac.manchester.tornado.api.KernelContext";
    public static final String TILE_CONTEXT = "uk.ac.manchester.tornado.api.tile.TileContext";

    /**
     * How a kernel expresses its parallelism.
     */
    public enum Kind {
        /** {@code @Parallel} loops, possibly with {@code @Reduce} parameters. */
        PARALLEL_LOOP,
        /** A {@code KernelContext} parameter. */
        KERNEL_CONTEXT,
        /** A {@code TileContext} parameter. */
        TILE_CONTEXT
    }

    private TornadoKernels() {
    }

    /**
     * Returns whether {@code method} is a TornadoVM kernel.
     */
    public static boolean isKernel(@NotNull PsiMethod method) {
        return kind(method) != null;
    }

    /**
     * Returns the kind of the kernel, or {@code null} if {@code method} is not a kernel.
     * A context parameter takes precedence over {@code @Parallel}/{@code @Reduce}
     * annotations. The result is cached until the next PSI change.
     */
    public static @Nullable Kind kind(@NotNull PsiMethod method) {
        return CachedValuesManager.getCachedValue(method, () -> CachedValueProvider.Result.create(
                Optional.ofNullable(computeKind(method)), PsiModificationTracker.MODIFICATION_COUNT)).orElse(null);
    }

    /**
     * Returns the kernels declared in {@code file}, in document order.
     */
    public static @NotNull List<PsiMethod> findKernels(@NotNull PsiFile file) {
        List<PsiMethod> kernels = new ArrayList<>();
        for (PsiMethod method : PsiTreeUtil.findChildrenOfType(file, PsiMethod.class)) {
            if (isKernel(method)) {
                kernels.add(method);
            }
        }
        return kernels;
    }

    /**
     * Returns whether {@code annotation} is TornadoVM's {@code @Parallel} or {@code @Reduce}.
     */
    public static boolean isKernelAnnotation(@NotNull PsiAnnotation annotation) {
        PsiClass annotationType = annotation.resolveAnnotationType();
        if (annotationType != null) {
            String qName = annotationType.getQualifiedName();
            return PARALLEL.equals(qName) || REDUCE.equals(qName);
        }
        String name = annotation.getQualifiedName();
        return name != null && (name.endsWith("Parallel") || name.endsWith("Reduce"));
    }

    /**
     * Returns whether {@code type} is TornadoVM's {@code KernelContext}.
     */
    public static boolean isKernelContextType(@Nullable PsiType type) {
        return isType(type, KERNEL_CONTEXT, "KernelContext");
    }

    /**
     * Returns whether {@code type} is TornadoVM's {@code TileContext}.
     */
    public static boolean isTileContextType(@Nullable PsiType type) {
        return isType(type, TILE_CONTEXT, "TileContext");
    }

    private static @Nullable Kind computeKind(PsiMethod method) {
        boolean kernelContext = false;
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            PsiType type = parameter.getType();
            if (isTileContextType(type)) {
                return Kind.TILE_CONTEXT;
            }
            kernelContext |= isKernelContextType(type);
        }
        if (kernelContext) {
            return Kind.KERNEL_CONTEXT;
        }
        for (PsiAnnotation annotation : PsiTreeUtil.findChildrenOfType(method, PsiAnnotation.class)) {
            if (isKernelAnnotation(annotation) && PsiTreeUtil.getParentOfType(annotation, PsiMethod.class) == method) {
                return Kind.PARALLEL_LOOP;
            }
        }
        return null;
    }

    private static boolean isType(@Nullable PsiType type, String qualifiedName, String simpleName) {
        if (type == null) return false;
        if (type instanceof PsiClassType classType) {
            PsiClass resolved = classType.resolve();
            if (resolved != null) {
                return qualifiedName.equals(resolved.getQualifiedName());
            }
        }
        return type.getCanonicalText().endsWith(simpleName);
    }
}
