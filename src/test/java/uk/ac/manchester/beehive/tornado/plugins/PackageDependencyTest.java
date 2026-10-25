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

package uk.ac.manchester.beehive.tornado.plugins;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards the package layering described in the package-info files: no package cycles, the
 * analysis core ({@code kernel}) depends on no other plugin package, the static rules depend
 * only on the core, and only the UI and the toolbar actions use {@code ui.*}.
 * <p>
 * Dependencies are the plugin classes a source file names, by import or fully qualified name,
 * outside comments. Wildcard imports of plugin packages are rejected, because they would hide
 * a dependency from this test.
 * </p>
 */
public class PackageDependencyTest {

    private static final String ROOT = "uk.ac.manchester.beehive.tornado.plugins";
    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    private static final Pattern REFERENCE = Pattern.compile("\\b" + Pattern.quote(ROOT) + "\\.((?:[a-z][a-z0-9_]*\\.)*)[A-Z]");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)//.*$");
    private static final Pattern WILDCARD_IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?" + Pattern.quote(ROOT) + "\\b[\\w.]*\\.\\*\\s*;");

    /** Package (relative to ROOT, "" for ROOT itself) to the other plugin packages it uses. */
    private static Map<String, Set<String>> dependencies;

    /** Source files that import a plugin package with a wildcard. */
    private static List<String> wildcardImports;

    @BeforeClass
    public static void readSources() throws IOException {
        assertTrue("run from the project directory: " + SOURCES.toAbsolutePath() + " not found", Files.isDirectory(SOURCES));
        dependencies = new TreeMap<>();
        wildcardImports = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher declared = PACKAGE.matcher(source);
                if (!declared.find() || !(declared.group(1) + ".").startsWith(ROOT + ".")) {
                    continue;
                }
                String from = relative(declared.group(1));
                Set<String> uses = dependencies.computeIfAbsent(from, p -> new TreeSet<>());
                String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll("");
                Matcher wildcard = WILDCARD_IMPORT.matcher(code);
                while (wildcard.find()) {
                    wildcardImports.add(SOURCES.relativize(file) + ": " + wildcard.group().strip());
                }
                Matcher reference = REFERENCE.matcher(code);
                while (reference.find()) {
                    String to = reference.group(1).isEmpty() ? "" : reference.group(1).substring(0, reference.group(1).length() - 1);
                    if (!to.equals(from)) {
                        uses.add(to);
                    }
                }
            }
        }
        assertTrue("too few plugin packages found under " + SOURCES + ": " + dependencies.keySet(),
                dependencies.size() >= 10 && dependencies.containsKey("kernel"));
    }

    @Test
    public void noPackageCycles() {
        List<String> packages = new ArrayList<>(dependencies.keySet());
        Map<String, Set<String>> reachable = new TreeMap<>();
        for (String start : packages) {
            Set<String> seen = new TreeSet<>();
            List<String> pending = new ArrayList<>(uses(start));
            while (!pending.isEmpty()) {
                String next = pending.remove(pending.size() - 1);
                if (seen.add(next)) {
                    pending.addAll(uses(next));
                }
            }
            reachable.put(start, seen);
        }
        Set<Set<String>> cycles = new LinkedHashSet<>();
        for (String p : packages) {
            Set<String> component = new TreeSet<>();
            for (String q : packages) {
                if (reachable.get(p).contains(q) && reachable.get(q).contains(p)) {
                    component.add(q);
                }
            }
            if (!component.isEmpty()) {
                cycles.add(component);
            }
        }
        if (!cycles.isEmpty()) {
            fail("package cycles: " + cycles + describe(cycles));
        }
    }

    @Test
    public void noWildcardImportsOfPluginPackages() {
        assertTrue("import plugin classes by name, not with a wildcard: " + wildcardImports, wildcardImports.isEmpty());
    }

    @Test
    public void kernelDependsOnNoOtherPluginPackage() {
        assertTrue("kernel must not use other plugin packages, uses " + uses("kernel"), uses("kernel").isEmpty());
    }

    @Test
    public void staticRulesDependOnlyOnTheCore() {
        Set<String> extra = new TreeSet<>(uses("inspection"));
        extra.removeAll(Set.of("kernel", "util"));
        assertTrue("inspection may use only kernel and util, also uses " + extra, extra.isEmpty());
    }

    @Test
    public void onlyTheUiAndTheActionsUseUi() {
        List<String> violations = new ArrayList<>();
        dependencies.forEach((from, uses) -> {
            if (from.equals("ui") || from.startsWith("ui.") || from.equals("actions")) {
                return;
            }
            for (String to : uses) {
                if (to.equals("ui") || to.startsWith("ui.")) {
                    violations.add(from + " -> " + to);
                }
            }
        });
        assertTrue("packages below the UI use ui.*: " + violations, violations.isEmpty());
    }

    private static Set<String> uses(String from) {
        return dependencies.getOrDefault(from, Set.of());
    }

    private static String describe(Set<Set<String>> cycles) {
        StringBuilder edges = new StringBuilder();
        for (Set<String> cycle : cycles) {
            for (String from : cycle) {
                for (String to : uses(from)) {
                    if (cycle.contains(to)) {
                        edges.append("\n  ").append(from).append(" -> ").append(to);
                    }
                }
            }
        }
        return edges.toString();
    }

    private static String relative(String packageName) {
        return packageName.equals(ROOT) ? "" : packageName.substring(ROOT.length() + 1);
    }
}
