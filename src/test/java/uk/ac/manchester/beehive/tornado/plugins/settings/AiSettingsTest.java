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

package uk.ac.manchester.beehive.tornado.plugins.settings;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import uk.ac.manchester.beehive.tornado.plugins.ai.ClaudeProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException;
import uk.ac.manchester.beehive.tornado.plugins.ai.ModelInfo;
import uk.ac.manchester.beehive.tornado.plugins.ai.OpenAiCompatibleProvider;

import java.util.List;

public class AiSettingsTest extends BasePlatformTestCase {

    public void testOffByDefaultWithLocalDefaults() {
        AiSettingState state = new AiSettingState();
        assertFalse(state.enabled);
        assertEquals(AiSettingState.Provider.LOCAL, state.provider);
        assertEquals(OpenAiCompatibleProvider.DEFAULT_URL, state.localUrl);
        assertEquals(ClaudeProvider.DEFAULT_MODEL, state.claudeModel);
        assertEquals(ClaudeProvider.Effort.HIGH, state.claudeEffort);
        assertEquals(8192, state.localMaxTokens);
    }

    public void testAnEditedAnswerLimitIsKeptInRange() {
        AiSettingState state = new AiSettingState();
        state.localMaxTokens = 10;
        assertEquals(AiSettingState.MIN_LOCAL_MAX_TOKENS, state.localMaxTokens());
        state.localMaxTokens = 10_000_000;
        assertEquals(AiSettingState.MAX_LOCAL_MAX_TOKENS, state.localMaxTokens());
    }

    public void testFormRoundTrip() {
        AiSettingState saved = new AiSettingState();
        saved.enabled = true;
        saved.provider = AiSettingState.Provider.CLAUDE;
        saved.localUrl = "http://gpu-box:8000/v1";
        saved.localModel = "qwen3";
        saved.localMaxTokens = 32_768;
        saved.claudeModel = "claude-sonnet-5-5";
        saved.claudeEffort = ClaudeProvider.Effort.MEDIUM;

        AiSettingsComponent form = new AiSettingsComponent();
        form.reset(saved, "local-key", "claude-key");
        AiSettingState shown = form.toState();
        assertTrue(shown.enabled);
        assertEquals(saved.provider, shown.provider);
        assertEquals(saved.localUrl, shown.localUrl);
        assertEquals(saved.localModel, shown.localModel);
        assertEquals(saved.localMaxTokens, shown.localMaxTokens);
        assertEquals(saved.claudeModel, shown.claudeModel);
        assertEquals(saved.claudeEffort, shown.claudeEffort);
        assertEquals("local-key", form.localKey());
        assertEquals("claude-key", form.claudeKey());
    }

    public void testValidateChecksTheUrlOnlyWhenTheLocalServerIsUsed() {
        AiSettingState saved = new AiSettingState();
        saved.localUrl = "localhost:8090";
        AiSettingsComponent form = new AiSettingsComponent();
        form.reset(saved, "", "");
        assertNull("disabled", form.validate());

        saved.enabled = true;
        form.reset(saved, "", "");
        assertNotNull(form.validate());

        saved.provider = AiSettingState.Provider.CLAUDE;
        form.reset(saved, "", "");
        assertNull(form.validate());

        saved.claudeModel = "claude opus";
        form.reset(saved, "", "");
        assertNotNull(form.validate());
    }

    public void testClaudeModelError() {
        assertNull(AiProviders.claudeModelError("claude-opus-5-5"));
        assertNull(AiProviders.claudeModelError("claude-sonnet-4-5-20250929"));
        // Only the Claude API is called, not Bedrock or Vertex
        assertNotNull(AiProviders.claudeModelError("anthropic.claude-opus-5-5-v1:0"));
        assertNotNull(AiProviders.claudeModelError(".."));
        assertNotNull(AiProviders.claudeModelError("claude opus"));
        assertNotNull(AiProviders.claudeModelError("../v1/messages?x=1"));

        AiSettingState state = new AiSettingState();
        state.provider = AiSettingState.Provider.CLAUDE;
        state.claudeModel = "claude opus";
        try {
            AiProviders.create(state, "sk-ant-test");
            fail();
        } catch (LlmException e) {
            assertEquals(LlmException.Kind.BAD_REQUEST, e.kind());
        }
    }

    public void testIsUnencryptedRemote() {
        assertFalse(AiProviders.isUnencryptedRemote("http://localhost:8090/v1"));
        assertFalse(AiProviders.isUnencryptedRemote("http://127.0.0.1:8090/v1"));
        assertFalse(AiProviders.isUnencryptedRemote("http://[::1]:8090/v1"));
        assertFalse(AiProviders.isUnencryptedRemote("https://gpu-box.example.org/v1"));
        assertTrue(AiProviders.isUnencryptedRemote("http://gpu-box.example.org:8090/v1"));
        assertTrue(AiProviders.isUnencryptedRemote("http://192.168.1.20:8090/v1"));
        assertFalse(AiProviders.isUnencryptedRemote("not a url"));
    }

    public void testUrlError() {
        assertNull(AiProviders.urlError("http://localhost:8090/v1"));
        assertNull(AiProviders.urlError("https://models.example.org/v1/"));
        assertNotNull(AiProviders.urlError(""));
        assertNotNull(AiProviders.urlError("localhost:8090"));
        assertNotNull(AiProviders.urlError("ftp://host/v1"));
        assertNotNull(AiProviders.urlError("http://bad host/v1"));
    }

    public void testCreate() throws LlmException {
        AiSettingState state = new AiSettingState();
        assertInstanceOf(AiProviders.create(state, ""), OpenAiCompatibleProvider.class);
        state.provider = AiSettingState.Provider.CLAUDE;
        assertInstanceOf(AiProviders.create(state, "sk-ant-test"), ClaudeProvider.class);

        state.provider = AiSettingState.Provider.LOCAL;
        state.localUrl = "not a url";
        try {
            AiProviders.create(state, "");
            fail();
        } catch (LlmException e) {
            assertEquals(LlmException.Kind.BAD_REQUEST, e.kind());
        }
    }

    public void testClaudeWithoutKeyNeedsTheEnvironment() {
        AiSettingState state = new AiSettingState();
        state.provider = AiSettingState.Provider.CLAUDE;
        try {
            AiProviders.create(state, "");
            assertNotNull("created only with " + AiCredentials.CLAUDE_KEY_VARIABLE, AiCredentials.claudeKeyFromEnvironment());
        } catch (LlmException e) {
            assertNull(AiCredentials.claudeKeyFromEnvironment());
            assertEquals(LlmException.Kind.AUTHENTICATION, e.kind());
            assertTrue(e.getMessage(), e.getMessage().contains(AiCredentials.CLAUDE_KEY_VARIABLE));
        }
    }

    public void testStoredKeyWinsOverTheEnvironment() {
        assertEquals("stored", AiCredentials.effective(AiSettingState.Provider.CLAUDE, " stored "));
        assertEquals("", AiCredentials.effective(AiSettingState.Provider.LOCAL, ""));
    }

    public void testCredentialsRoundTrip() {
        AiCredentials.store(AiSettingState.Provider.LOCAL, "secret");
        try {
            assertEquals("secret", AiCredentials.stored(AiSettingState.Provider.LOCAL));
            assertEquals("", AiCredentials.stored(AiSettingState.Provider.CLAUDE));
        } finally {
            AiCredentials.store(AiSettingState.Provider.LOCAL, "");
        }
        assertEquals("", AiCredentials.stored(AiSettingState.Provider.LOCAL));
    }

    public void testConnectedMessage() {
        assertEquals("Connected: gemma (context 131,072 tokens)",
                AiSettingsComponent.connected(AiSettingState.Provider.LOCAL, new ModelInfo(List.of("gemma"), 131072)));
        assertEquals("Connected: a, b", AiSettingsComponent.connected(AiSettingState.Provider.LOCAL, new ModelInfo(List.of("a", "b"), null)));
        assertTrue(AiSettingsComponent.connected(AiSettingState.Provider.CLAUDE, new ModelInfo(List.of("claude-opus-5-5"), 1000000))
                .contains("input tokens"));
    }
}
