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

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConversionRulesTest {

    // Inspections that are not about the code inside a kernel
    private static final Set<String> NOT_KERNEL_RULES = Set.of("StaticTaskGraphInspection");

    @Test
    public void everyKernelInspectionHasARule() throws IOException {
        Set<String> inspections = new TreeSet<>();
        try (InputStream in = ConversionRulesTest.class.getResourceAsStream("/META-INF/plugin.xml")) {
            assertNotNull(in);
            Matcher shortName = Pattern.compile("shortName=\"(\\w+)\"").matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (shortName.find()) {
                inspections.add(shortName.group(1));
            }
        }
        inspections.removeAll(NOT_KERNEL_RULES);
        assertTrue(inspections.toString(), inspections.size() >= 8);
        assertEquals(inspections, ConversionRules.checkedInspections());
    }

    @Test
    public void commentsAreNotSent() {
        String text = ConversionRules.text(Target.LOOP_PARALLEL);
        assertFalse(text.contains("<!--"));
        assertFalse(text.contains("check:"));
        assertTrue(text.startsWith("# Converting a Java method"));
        assertTrue(text.contains("for (@Parallel int i = 0; i < n; i++)"));
    }

    @Test
    public void eachTargetHasItsOwnSection() {
        String loopParallel = ConversionRules.text(Target.LOOP_PARALLEL);
        String kernelApi = ConversionRules.text(Target.KERNEL_API);
        for (String text : List.of(loopParallel, kernelApi)) {
            assertFalse(text.contains(ConversionRules.TARGET));
            assertTrue(text.contains("## Not allowed inside a kernel"));
        }
        assertTrue(loopParallel.contains("## The API: Loop Parallel"));
        assertFalse(loopParallel.contains("KernelContext context"));
        assertTrue(kernelApi.contains("## The API: Kernel API"));
        assertTrue(kernelApi.contains("// grid: n, n"));
        assertFalse(kernelApi.contains("for (@Parallel int i = 0; i < n; i++)"));
    }

    @Test
    public void answerFormats() {
        assertEquals(new Answer("void f() { }", "Done."), Answer.parse("```java\nvoid f() { }\n```\nDone."));
        assertEquals(new Answer("void f() { }", "x"), Answer.parse("{\"code\": \"void f() { }\", \"explanation\": \"x\"}"));
        assertEquals("void f() { }", Answer.parse("{\"code\": \"```java\\nvoid f() { }\\n```\"}").code());
        assertEquals("public static void f() { }", Answer.parse("public static void f() { }").code());
        // The first block with code, not a block of plain text
        assertEquals("void g() { }", Answer.parse("```\nnotes\n```\n```java\nvoid g() { }\n```").code());
        assertNull(Answer.parse("I cannot convert this method."));
        assertNull(Answer.parse("{\"explanation\": \"no code\"}"));
        // The model may answer that the method cannot be parallelised
        assertEquals("y[i] needs y[i - 1].", Answer.parse(
                "{\"code\": \"\", \"explanation\": \"y[i] needs y[i - 1].\", \"parallelizable\": false}").notParallel());
        assertNull(Answer.parse("{\"code\": \"void f() { }\", \"explanation\": \"x\", \"parallelizable\": true}").notParallel());
        assertEquals("Each step needs the last.", Answer.parse("CANNOT PARALLELIZE: Each step needs the last.").notParallel());
        assertEquals("Each step needs the last.", Answer.parse("**CANNOT PARALLELISE:** Each step needs the last.").notParallel());
    }
}
