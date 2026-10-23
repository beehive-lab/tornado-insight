/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * The plugin's message bundle, {@code messages/TornadoInsightBundle.properties}.
 * <p>
 * Messages without parameters are returned as written. Messages with parameters
 * are formatted with {@link java.text.MessageFormat}, so a literal single quote in
 * such a message is written as {@code ''}.
 * </p>
 */
public final class MessageBundle {
    public static final @NonNls String BUNDLE = "messages.TornadoInsightBundle";

    private static final DynamicBundle INSTANCE = new DynamicBundle(MessageBundle.class, BUNDLE);

    private MessageBundle() {
    }

    /**
     * Retrieves the message for {@code key}, substituting the {@code {0}}, {@code {1}}, ...
     * placeholders with {@code params} when there are any.
     *
     * @param key    The key for which the message is desired.
     * @param params The values substituted into the message placeholders.
     * @return The localized message for the provided key.
     */
    public static @NotNull @Nls String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
                                               Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
