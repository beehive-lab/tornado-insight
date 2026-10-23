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

import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The data types TornadoVM accepts inside a kernel, read from the
 * {@value #RESOURCE} resource (MPLR'25, §3.1.1: the supported types are kept in a
 * configuration file rather than in code). The file format is documented in its
 * header.
 * <p>
 * The resource ships inside the plugin jar, so a missing or malformed file is a
 * build defect: {@link #get()} throws {@link IllegalStateException} instead of
 * falling back to a second, embedded copy of the list that could silently drift.
 * The failure is not cached, so every caller sees the same descriptive error.
 * </p>
 */
public final class SupportedKernelTypes {

    static final String RESOURCE = "/tornado/supported-kernel-types.txt";

    private static volatile SupportedKernelTypes instance;

    private final List<String> names;
    private final List<String> prefixes;

    private SupportedKernelTypes(List<String> names, List<String> prefixes) {
        this.names = List.copyOf(names);
        this.prefixes = List.copyOf(prefixes);
    }

    /**
     * Returns the types read from {@value #RESOURCE}, loading them on first use.
     *
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static @NotNull SupportedKernelTypes get() {
        SupportedKernelTypes result = instance;
        if (result == null) {
            synchronized (SupportedKernelTypes.class) {
                result = instance;
                if (result == null) {
                    instance = result = load();
                }
            }
        }
        return result;
    }

    private static SupportedKernelTypes load() {
        try (InputStream in = SupportedKernelTypes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(reader.lines().toList(), RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    /**
     * Parses the lines of a supported-types file.
     *
     * @param source name of the file, for error messages
     * @throws IllegalStateException if the content is malformed
     */
    static @NotNull SupportedKernelTypes parse(@NotNull List<String> lines, @NotNull String source) {
        List<String> names = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        List<String> section = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("[names]")) {
                section = names;
            } else if (line.equals("[prefixes]")) {
                section = prefixes;
            } else if (line.startsWith("[")) {
                throw malformed(source, i, "unknown section " + line);
            } else if (section == null) {
                throw malformed(source, i, "entry outside a section");
            } else if (line.chars().anyMatch(Character::isWhitespace)) {
                throw malformed(source, i, "entry contains whitespace");
            } else {
                section.add(line);
            }
        }
        if (names.isEmpty() || prefixes.isEmpty()) {
            throw new IllegalStateException("malformed " + source + ": [names] and [prefixes] must both list types");
        }
        return new SupportedKernelTypes(names, prefixes);
    }

    private static IllegalStateException malformed(String source, int index, String reason) {
        return new IllegalStateException("malformed " + source + " line " + (index + 1) + ": " + reason);
    }

    /** Types matched by {@link PsiType#equalsToText}. */
    public @NotNull List<String> names() {
        return names;
    }

    /** Prefixes matched against {@link PsiType#getCanonicalText()}. */
    public @NotNull List<String> prefixes() {
        return prefixes;
    }

    /**
     * Returns whether {@code type} may be used inside a kernel. Unresolved class
     * types are not special-cased here; callers that cannot classify them skip them.
     */
    public boolean isSupported(@NotNull PsiType type) {
        for (String name : names) {
            if (type.equalsToText(name)) return true;
        }
        String text = type.getCanonicalText();
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }
}
