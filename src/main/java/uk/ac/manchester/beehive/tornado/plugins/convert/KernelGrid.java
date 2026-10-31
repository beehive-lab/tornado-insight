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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The launch grid that a Kernel API or Tile API kernel declares in comments at the start of its
 * body: {@code // grid: n, n} (threads, or tile blocks, per dimension) and optionally
 * {@code // local: 16, 16} (the work-group size). Each size is a Java {@code int} expression over
 * the kernel's parameters.
 *
 * @param global the sizes of the grid, one to three
 * @param local  the work-group sizes, or empty to leave them to TornadoVM
 */
record KernelGrid(@NotNull List<String> global, @NotNull List<String> local) {

    private static final Pattern GRID = Pattern.compile("//\\s*grid\\s*:\\s*(.+)");
    private static final Pattern LOCAL = Pattern.compile("//\\s*local\\s*:\\s*(.+)");

    KernelGrid {
        global = List.copyOf(global);
        local = List.copyOf(local);
    }

    /** The grid declared in {@code kernelText}, or {@code null} if it declares none. */
    static @Nullable KernelGrid parse(@NotNull String kernelText) {
        Matcher grid = GRID.matcher(kernelText);
        if (!grid.find()) {
            return null;
        }
        Matcher local = LOCAL.matcher(kernelText);
        return new KernelGrid(split(grid.group(1)), local.find() ? split(local.group(1)) : List.of());
    }

    /** The grid as the user writes it, e.g. {@code new WorkerGrid2D(n, n)}. */
    @NotNull String workerGrid() {
        return "new WorkerGrid" + global.size() + "D(" + String.join(", ", global) + ")";
    }

    // Splits at the commas outside parentheses, so Math.max(a, b) stays one size
    private static List<String> split(String sizes) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder part = new StringBuilder();
        for (char c : sizes.strip().toCharArray()) {
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(part.toString().strip());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        if (!part.isEmpty()) {
            parts.add(part.toString().strip());
        }
        return parts;
    }
}
