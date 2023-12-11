/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
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

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Utility class to retrieve localized messages from a resource bundle.
 * Default language is set to English (en).
 */
public class MessageBundle {
    static String langCode = "en";

    // Private constructor to prevent instantiation of utility class
    private MessageBundle() {
    }

    /**
     * Retrieves a localized message for the provided key.
     *
     * @param key The key for which the message is desired.
     * @return Localized message for the provided key.
     */
    public static @NotNull String message(String key) {
        return ResourceBundle.getBundle("messages.plugin", Locale.forLanguageTag(langCode)).getString(key);
    }
}
