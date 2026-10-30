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

import org.jetbrains.annotations.NotNull;
import uk.ac.manchester.beehive.tornado.plugins.ai.ClaudeProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmProvider;
import uk.ac.manchester.beehive.tornado.plugins.ai.OpenAiCompatibleProvider;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creates the {@link LlmProvider} that the AI settings select.
 */
public final class AiProviders {

    // A model id of the Claude API (claude-opus-5-5), which also goes into a URL path
    private static final Pattern CLAUDE_MODEL = Pattern.compile("claude[A-Za-z0-9._-]*");

    private AiProviders() {
    }

    /**
     * The provider of the saved settings. Reads the password store, so call it off the EDT.
     *
     * @throws LlmException AUTHENTICATION if Claude is selected and no key is set
     */
    public static @NotNull LlmProvider fromSettings() throws LlmException {
        AiSettingState settings = AiSettingState.getInstance();
        return create(settings, AiCredentials.stored(settings.provider));
    }

    /**
     * @param storedKey the key typed or stored for the selected provider, possibly empty
     */
    public static @NotNull LlmProvider create(@NotNull AiSettingState settings, @NotNull String storedKey) throws LlmException {
        String key = AiCredentials.effective(settings.provider, storedKey);
        if (settings.provider == AiSettingState.Provider.CLAUDE) {
            if (key.isEmpty()) {
                throw new LlmException(LlmException.Kind.AUTHENTICATION,
                        MessageBundle.message("ai.error.noClaudeKey", AiCredentials.CLAUDE_KEY_VARIABLE));
            }
            String model = settings.claudeModel == null || settings.claudeModel.isBlank()
                    ? ClaudeProvider.DEFAULT_MODEL : settings.claudeModel.strip();
            String error = claudeModelError(model);
            if (error != null) {
                throw new LlmException(LlmException.Kind.BAD_REQUEST, error);
            }
            return new ClaudeProvider(key, model, settings.claudeEffort);
        }
        String error = urlError(settings.localUrl);
        if (error != null) {
            throw new LlmException(LlmException.Kind.BAD_REQUEST, error);
        }
        return new OpenAiCompatibleProvider(settings.localUrl, key, settings.localModel);
    }

    /** Why {@code model} cannot be a Claude model id, or {@code null} if it can. */
    public static String claudeModelError(String model) {
        return model != null && CLAUDE_MODEL.matcher(model.strip()).matches()
                ? null : MessageBundle.message("ai.settings.validation.claudeModel");
    }

    /**
     * Whether {@code url} is plain HTTP to another machine, so that the code and the key would
     * cross the network unencrypted. Does not look up host names.
     */
    public static boolean isUnencryptedRemote(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.strip());
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return false;
            }
            if (host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
                return false;
            }
            // Only literal addresses: a name is not resolved here
            boolean literal = host.startsWith("[") || host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
            return !literal || !InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Why {@code url} cannot be a server's base URL, or {@code null} if it can. */
    public static String urlError(String url) {
        try {
            URI uri = URI.create(url == null ? "" : url.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return null;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        return MessageBundle.message("ai.settings.validation.url");
    }
}
