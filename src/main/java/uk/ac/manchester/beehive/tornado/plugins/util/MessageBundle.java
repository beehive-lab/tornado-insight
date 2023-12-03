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
