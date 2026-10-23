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

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards plugin.xml against class names left stale by a package move: every
 * plugin class it references must load.
 */
public class PluginXmlClassNamesTest {

    private static final String PLUGIN_ID = "uk.ac.manchester.beehive.TornadoInsight";

    private static final Set<String> CLASS_ATTRIBUTES = Set.of("class", "implementation", "implementationClass",
            "instance", "factoryClass", "serviceImplementation", "serviceInterface", "topic");

    @Test
    public void referencedPluginClassesExist() throws Exception {
        List<String> names = pluginClassNames(ourPluginXml());
        assertTrue("no plugin classes found in plugin.xml: " + names, names.size() >= 10);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            try {
                // Binary names: nested classes are written with '$', as Class.forName expects
                Class.forName(name, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            fail("plugin.xml references classes that do not exist: " + missing);
        }
    }

    private static Document ourPluginXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Other plugins on the test classpath ship a META-INF/plugin.xml too
        for (URL url : Collections.list(PluginXmlClassNamesTest.class.getClassLoader().getResources("META-INF/plugin.xml"))) {
            try (InputStream in = url.openStream()) {
                Document document = factory.newDocumentBuilder().parse(in);
                NodeList ids = document.getDocumentElement().getElementsByTagName("id");
                if (ids.getLength() > 0 && PLUGIN_ID.equals(ids.item(0).getTextContent().strip())) {
                    return document;
                }
            }
        }
        throw new AssertionError("META-INF/plugin.xml of " + PLUGIN_ID + " is not on the test classpath");
    }

    private static List<String> pluginClassNames(Document document) {
        List<String> names = new ArrayList<>();
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            for (String attribute : CLASS_ATTRIBUTES) {
                String value = element.getAttribute(attribute).strip();
                if (value.startsWith("uk.ac.manchester.beehive")) {
                    names.add(value);
                }
            }
        }
        return names;
    }
}
