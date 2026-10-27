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

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The import statements of a harness: those copied from the user's files, plus the
 * ones the generated code needs. Each statement is emitted once, and a type is
 * imported only if its simple name does not clash with another imported type, so
 * the harness can use simple names wherever that compiles.
 */
final class HarnessImports {

    private static final Pattern SINGLE_TYPE = Pattern.compile("import ([\\w.]+)\\.(\\w+);");

    private final TreeSet<String> statements = new TreeSet<>();
    // simple name -> qualified name of every single-type import
    private final Map<String, String> imported = new HashMap<>();

    /**
     * @param userImports the import statements copied from the user's files
     */
    HarnessImports(@NotNull Iterable<String> userImports) {
        for (String statement : userImports) {
            add(normalise(statement));
        }
    }

    /**
     * Imports {@code qualifiedName} unless its simple name is taken by another type.
     *
     * @return whether the simple name now refers to {@code qualifiedName}
     */
    boolean require(@NotNull String qualifiedName) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        String owner = imported.get(simple);
        if (owner != null) {
            return owner.equals(qualifiedName);
        }
        imported.put(simple, qualifiedName);
        statements.add("import " + qualifiedName + ";");
        return true;
    }

    /**
     * Returns how the harness writes {@code type}, a canonical type text: the simple
     * name, imported if needed, or the type unchanged when the simple name would not
     * resolve to it (a clash, a generic type, or a type that did not resolve).
     */
    @NotNull String use(@NotNull String type) {
        int dims = type.indexOf('[');
        String element = dims < 0 ? type : type.substring(0, dims);
        String brackets = dims < 0 ? "" : type.substring(dims);
        int dot = element.lastIndexOf('.');
        // Primitives and unresolved names have no package; generics stay as they are
        if (dot < 0 || element.contains("<") || !Character.isLowerCase(element.charAt(0))) {
            return type;
        }
        String simple = element.substring(dot + 1);
        if (element.equals("java.lang." + simple)) {
            return simple + brackets;
        }
        return require(element) ? simple + brackets : type;
    }

    /**
     * @return the import statements, sorted, one per line
     */
    @NotNull String source() {
        StringBuilder source = new StringBuilder();
        for (String statement : statements) {
            source.append(statement).append("\n");
        }
        return source.toString();
    }

    private void add(String statement) {
        Matcher single = SINGLE_TYPE.matcher(statement);
        if (single.matches()) {
            imported.putIfAbsent(single.group(2), single.group(1) + "." + single.group(2));
        }
        statements.add(statement);
    }

    // "import  a.b.C ;" -> "import a.b.C;"
    static @NotNull String normalise(@NotNull String statement) {
        return statement.strip().replaceAll("\\s+", " ").replaceAll("\\s*([.;*])\\s*", "$1");
    }
}
