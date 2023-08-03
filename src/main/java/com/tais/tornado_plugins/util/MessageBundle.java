package com.tais.tornado_plugins.util;

import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.ResourceBundle;

public class MessageBundle {
    static String langCode = "en";
    private MessageBundle(){}
    public static @NotNull String message(String key) {
        return ResourceBundle.getBundle("messages.plugin", Locale.forLanguageTag(langCode)).getString(key);
    }
}
