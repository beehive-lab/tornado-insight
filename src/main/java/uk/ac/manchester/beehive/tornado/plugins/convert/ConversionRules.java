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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TornadoVM rules given to the model as the system prompt, read from {@value #RESOURCE} with
 * the section of the target ({@link Target#rules()}) in place of its {@value #TARGET} comment.
 */
final class ConversionRules {

    static final String RESOURCE = "/ai/tornadovm-rules.md";
    static final String TARGET = "<!-- target -->";

    private static final Pattern COMMENT = Pattern.compile("(?s)\\s*<!--(.*?)-->");
    private static final Pattern CHECK = Pattern.compile("check:\\s*(\\w+)");

    private ConversionRules() {
    }

    /** The rules for {@code target} as sent to the model, without the maintainers' comments. */
    static @NotNull String text(@NotNull Target target) {
        String rules = source(RESOURCE).replace(TARGET, source(target.rules()).strip());
        return COMMENT.matcher(rules).replaceAll("").replaceAll("\n{3,}", "\n\n").strip();
    }

    /** The inspections that the rules of any target name in their {@code check:} comments. */
    static @NotNull Set<String> checkedInspections() {
        StringBuilder all = new StringBuilder(source(RESOURCE));
        for (Target target : Target.values()) {
            all.append('\n').append(source(target.rules()));
        }
        Set<String> names = new TreeSet<>();
        Matcher comment = COMMENT.matcher(all);
        while (comment.find()) {
            Matcher check = CHECK.matcher(comment.group(1));
            while (check.find()) {
                names.add(check.group(1));
            }
        }
        return names;
    }

    private static String source(String resource) {
        try (InputStream in = ConversionRules.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
