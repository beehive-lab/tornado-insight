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

package uk.ac.manchester.beehive.tornado.plugins.templates;

import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The TornadoVM live templates the plugin ships, read from {@code liveTemplates/TornadoVM.xml}
 * so that the Live Templates tab always lists what the plugin registers.
 */
public final class TornadoVMTemplates {

    static final String RESOURCE = "/liveTemplates/TornadoVM.xml";

    /** A TornadoVM API class written with its package, as the templates do; shown by its simple name. */
    private static final Pattern API_CLASS = Pattern.compile("\\buk\\.ac\\.manchester\\.tornado\\.api(?:\\.[a-z]\\w*)*\\.([A-Z]\\w*)");
    private static final Pattern VARIABLE = Pattern.compile("\\$(\\w+)\\$");

    /**
     * One template.
     *
     * @param abbreviation what the user types before pressing Tab
     * @param description  the template's description
     * @param contexts     the ids of the contexts it is enabled in, e.g. {@code TORNADOVM_STATEMENT}
     * @param preview      the inserted code, with each variable shown as its default value or,
     *                     without one, as its name
     */
    public record Template(@NotNull String abbreviation, @NotNull String description, @NotNull List<String> contexts, @NotNull String preview) {
    }

    private static volatile List<Template> templates;

    private TornadoVMTemplates() {
    }

    /** The templates in file order. Throws when the resource is missing or malformed. */
    public static @NotNull List<Template> all() {
        List<Template> result = templates;
        if (result == null) {
            synchronized (TornadoVMTemplates.class) {
                result = templates;
                if (result == null) {
                    try (InputStream in = TornadoVMTemplates.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) {
                            throw new IllegalStateException(RESOURCE + " is not on the plugin classpath");
                        }
                        result = parse(in);
                    } catch (IllegalStateException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot read " + RESOURCE, e);
                    }
                    templates = result;
                }
            }
        }
        return result;
    }

    static @NotNull List<Template> parse(@NotNull InputStream in) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement();
        NodeList elements = root.getElementsByTagName("template");
        List<Template> result = new ArrayList<>();
        for (int i = 0; i < elements.getLength(); i++) {
            Element template = (Element) elements.item(i);
            Map<String, String> defaults = new HashMap<>();
            NodeList variables = template.getElementsByTagName("variable");
            for (int j = 0; j < variables.getLength(); j++) {
                Element variable = (Element) variables.item(j);
                defaults.put(variable.getAttribute("name"), unquote(variable.getAttribute("defaultValue")));
            }
            List<String> contexts = new ArrayList<>();
            NodeList options = template.getElementsByTagName("option");
            for (int j = 0; j < options.getLength(); j++) {
                Element option = (Element) options.item(j);
                if ("true".equals(option.getAttribute("value"))) {
                    contexts.add(option.getAttribute("name"));
                }
            }
            result.add(new Template(template.getAttribute("name"), template.getAttribute("description"), List.copyOf(contexts),
                    preview(template.getAttribute("value"), defaults)));
        }
        return List.copyOf(result);
    }

    private static String preview(String value, Map<String, String> defaults) {
        Matcher variable = VARIABLE.matcher(value);
        StringBuilder text = new StringBuilder();
        while (variable.find()) {
            String name = variable.group(1);
            String shown = "";
            if (!name.equals("END")) {
                String defaultValue = defaults.getOrDefault(name, "");
                shown = defaultValue.isEmpty() ? name : defaultValue;
            }
            variable.appendReplacement(text, Matcher.quoteReplacement(shown));
        }
        variable.appendTail(text);
        String shortened = API_CLASS.matcher(text).replaceAll("$1");
        // Removing $END$ can leave a whitespace-only line behind
        return shortened.lines().map(String::stripTrailing).collect(Collectors.joining("\n")).strip();
    }

    /** Template default values are expressions; a plain string default is written in quotes. */
    private static String unquote(String expression) {
        String trimmed = expression.strip();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"") ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }
}
