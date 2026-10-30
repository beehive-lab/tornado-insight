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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.ai.ClaudeProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.OpenAiCompatibleProvider;

/**
 * The settings of AI-assisted transformation, without the API keys (see {@link AiCredentials}).
 * Transformation is off until the user enables it, because it sends code to a model.
 */
@State(name = "TornadoInsightAiSettings", storages = {@Storage(value = "TornadoSettings.xml")})
public class AiSettingState implements PersistentStateComponent<AiSettingState> {

    /** Where the model runs. */
    public enum Provider {
        /** A server with the OpenAI chat API on the user's machine or network, such as jitllm. */
        LOCAL,
        /** Anthropic's Claude API. */
        CLAUDE
    }

    public boolean enabled;
    public Provider provider = Provider.LOCAL;
    public String localUrl = OpenAiCompatibleProvider.DEFAULT_URL;
    // Empty: the server's first model
    public String localModel = "";
    public String claudeModel = ClaudeProvider.DEFAULT_MODEL;
    public ClaudeProvider.Effort claudeEffort = ClaudeProvider.Effort.HIGH;
    /**
     * The longest answer a local model may write, in tokens. A kernel takes 1K to 3K; more is
     * for models that reason before answering, and lets a model that rambles run for minutes.
     */
    public int localMaxTokens = DEFAULT_LOCAL_MAX_TOKENS;

    public static final int DEFAULT_LOCAL_MAX_TOKENS = 8192;
    public static final int MIN_LOCAL_MAX_TOKENS = 1024;
    public static final int MAX_LOCAL_MAX_TOKENS = 131_072;

    /** {@link #localMaxTokens}, within the allowed range even if the settings file was edited. */
    public int localMaxTokens() {
        return Math.max(MIN_LOCAL_MAX_TOKENS, Math.min(MAX_LOCAL_MAX_TOKENS, localMaxTokens));
    }

    public static AiSettingState getInstance() {
        return ApplicationManager.getApplication().getService(AiSettingState.class);
    }

    @Override
    public @NotNull AiSettingState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull AiSettingState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
