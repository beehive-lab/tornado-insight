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

import org.junit.Test;
import uk.ac.manchester.beehive.tornado.plugins.templates.TornadoVMTemplates.Template;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Checks what the Live Templates tab shows: every shipped template, readable previews, and a
 * label for every context the templates use.
 */
public class TornadoVMTemplatesTest {

    @Test
    public void listsEveryShippedTemplateInFileOrder() {
        List<String> abbreviations = TornadoVMTemplates.all().stream().map(Template::abbreviation).toList();
        assertEquals(List.of("tg", "tep", "kpar", "kctx", "ktile"), abbreviations);
        for (Template template : TornadoVMTemplates.all()) {
            assertFalse(template.abbreviation() + " has no description", template.description().isBlank());
            assertFalse(template.abbreviation() + " has no context", template.contexts().isEmpty());
        }
    }

    @Test
    public void previewShowsDefaultsAndSimpleNames() {
        Template taskGraph = TornadoVMTemplates.all().get(0);
        assertEquals("""
                TaskGraph taskGraph = new TaskGraph("s0")
                        .transferToDevice(DataTransferMode.FIRST_EXECUTION, INPUTS)
                        .task("t0", CLASS::METHOD, ARGS)
                        .transferToHost(DataTransferMode.EVERY_EXECUTION, OUTPUTS);""", taskGraph.preview());
        for (Template template : TornadoVMTemplates.all()) {
            assertFalse(template.abbreviation() + ": " + template.preview(), template.preview().contains("$"));
            assertFalse(template.abbreviation() + ": " + template.preview(), template.preview().contains("uk.ac.manchester"));
        }
    }

    @Test
    public void previewDropsTheEmptyEndLine() {
        Template parallel = TornadoVMTemplates.all().stream().filter(t -> t.abbreviation().equals("kpar")).findFirst().orElseThrow();
        assertEquals("""
                public static void compute(PARAMS) {
                    for (@Parallel int i = 0; i < SIZE; i++) {

                    }
                }""", parallel.preview());
    }

    @Test
    public void everyContextHasALabel() throws Exception {
        Properties bundle = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/messages/TornadoInsightBundle.properties")) {
            assertNotNull(in);
            bundle.load(in);
        }
        for (Template template : TornadoVMTemplates.all()) {
            for (String context : template.contexts()) {
                assertTrue("no label for " + context, bundle.containsKey("templates.context." + context));
            }
        }
    }
}
