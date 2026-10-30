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

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The API keys of the AI providers, kept in the IDE's password store rather than in
 * the settings file. The Claude key falls back to {@code ANTHROPIC_API_KEY}.
 */
public final class AiCredentials {

    public static final String CLAUDE_KEY_VARIABLE = "ANTHROPIC_API_KEY";

    private static final CredentialAttributes CLAUDE = attributes("claude");
    private static final CredentialAttributes LOCAL = attributes("local");

    private AiCredentials() {
    }

    /** The key stored for {@code provider}, or "" if none is stored. */
    public static @NotNull String stored(@NotNull AiSettingState.Provider provider) {
        String key = PasswordSafe.getInstance().getPassword(attributesOf(provider));
        return key == null ? "" : key;
    }

    /** Stores {@code key} for {@code provider}; an empty key removes the stored one. */
    public static void store(@NotNull AiSettingState.Provider provider, @NotNull String key) {
        PasswordSafe.getInstance().setPassword(attributesOf(provider), key.isBlank() ? null : key.strip());
    }

    /** The Claude key from the environment, or {@code null}. */
    public static @Nullable String claudeKeyFromEnvironment() {
        String key = System.getenv(CLAUDE_KEY_VARIABLE);
        return key == null || key.isBlank() ? null : key.strip();
    }

    /** The key to use: the stored one, else for Claude the environment's, else "". */
    public static @NotNull String effective(@NotNull AiSettingState.Provider provider, @NotNull String stored) {
        if (!stored.isBlank() || provider != AiSettingState.Provider.CLAUDE) {
            return stored.strip();
        }
        String environment = claudeKeyFromEnvironment();
        return environment == null ? "" : environment;
    }

    private static CredentialAttributes attributesOf(AiSettingState.Provider provider) {
        return provider == AiSettingState.Provider.CLAUDE ? CLAUDE : LOCAL;
    }

    private static CredentialAttributes attributes(String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("TornadoInsight AI", key));
    }
}
