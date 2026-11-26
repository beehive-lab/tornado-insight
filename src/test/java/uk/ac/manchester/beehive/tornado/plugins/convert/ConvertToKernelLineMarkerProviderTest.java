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

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.codeInsight.daemon.LineMarkerProviders;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.actionSystem.ActionManager;
import icons.TornadoIcons;
import uk.ac.manchester.beehive.tornado.plugins.TornadoTestCase;
import uk.ac.manchester.beehive.tornado.plugins.settings.AiSettingState;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConvertToKernelLineMarkerProviderTest extends TornadoTestCase {

    private static final String SOURCE = """
            import uk.ac.manchester.tornado.api.annotations.Parallel;
            class Demo {
                void add(int[] a, int[] b, int[] c) {
                    for (int i = 0; i < c.length; i++) {
                        c[i] = a[i] + b[i];
                    }
                }
                int one() {
                    return 1;
                }
                void kernel(int[] a) {
                    for (@Parallel int i = 0; i < a.length; i++) {
                        a[i] = 0;
                    }
                }
                Demo(int[] a) {
                    for (int i = 0; i < a.length; i++) {
                        a[i] = 0;
                    }
                }
            }""";

    private boolean wasEnabled;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        wasEnabled = AiSettingState.getInstance().enabled;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            AiSettingState.getInstance().enabled = wasEnabled;
        } finally {
            super.tearDown();
        }
    }

    public void testIconOnlyOnConvertibleMethodsWithALoop() {
        AiSettingState.getInstance().enabled = true;
        addTornadoApi();
        myFixture.configureByText("Demo.java", SOURCE);
        // Not the method without a loop, the kernel or the constructor
        assertEquals(List.of("Transform add into a TornadoVM kernel (AI)..."), tooltips());
    }

    public void testNoIconWhileTheTransformationIsOff() {
        AiSettingState.getInstance().enabled = false;
        addTornadoApi();
        myFixture.configureByText("Demo.java", SOURCE);
        assertEmpty(tooltips());
    }

    public void testNoIconWithoutTheTornadoApi() {
        // The editor menu says which dependency to add; the gutter stays out of other projects
        AiSettingState.getInstance().enabled = true;
        myFixture.configureByText("Demo.java", SOURCE);
        assertEmpty(tooltips());
    }

    public void testRegisteredForJava() {
        assertTrue(LineMarkerProviders.getInstance().allForLanguage(JavaLanguage.INSTANCE).stream()
                .anyMatch(ConvertToKernelLineMarkerProvider.class::isInstance));
    }

    public void testEditorMenuItemHasTheSameIcon() {
        assertSame(TornadoIcons.AiTransform,
                ActionManager.getInstance().getAction("tornado.convertToKernel").getTemplatePresentation().getIcon());
    }

    public void testIconHasALightAndADarkVariantOf16By16() throws IOException {
        for (String path : List.of("/icons/aiTransform.svg", "/icons/aiTransform_dark.svg")) {
            try (InputStream in = TornadoIcons.class.getResourceAsStream(path)) {
                assertNotNull(path, in);
                String svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(path, svg.contains("width=\"16\" height=\"16\" viewBox=\"0 0 16 16\""));
            }
        }
    }

    private List<String> tooltips() {
        return myFixture.findAllGutters().stream()
                .map(GutterMark::getTooltipText)
                .filter(text -> text != null && text.contains("TornadoVM kernel (AI)"))
                .toList();
    }
}
