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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The code and explanation in a model's answer: a JSON object with {@code code} and
 * {@code explanation} (structured output), a fenced code block, or bare code. Or the model's
 * answer that the method cannot be parallelised: {@code "parallelizable": false} in the JSON, or
 * the text {@link #NOT_PARALLEL}.
 *
 * @param notParallel why the method cannot be parallelised, if the model says so; {@code code} is then empty
 */
record Answer(@NotNull String code, @NotNull String explanation, @Nullable String notParallel) {

    /** Starts an answer in text that the method cannot be parallelised. */
    static final String NOT_PARALLEL = "CANNOT PARALLELIZE:";

    private static final Pattern NOT_PARALLEL_TEXT = Pattern.compile("(?s)^[\\s*#]*CANNOT PARALLELI[SZ]E\\**:?\\**\\s*(.*)");
    private static final Pattern FENCE = Pattern.compile("(?s)```[a-zA-Z]*[ \\t]*\\n(.*?)```");
    private static final Pattern BARE_CODE = Pattern.compile("(?s)^\\s*(@|public|private|protected|static|void)\\b.*");

    Answer(@NotNull String code, @NotNull String explanation) {
        this(code, explanation, null);
    }

    /** Returns the answer in {@code text}, or {@code null} if it contains no code. */
    static @Nullable Answer parse(@NotNull String text) {
        Answer json = json(text);
        if (json != null) {
            return json;
        }
        Matcher notParallel = NOT_PARALLEL_TEXT.matcher(text);
        if (notParallel.matches()) {
            return refusal(notParallel.group(1));
        }
        Matcher fence = FENCE.matcher(text);
        while (fence.find()) {
            String code = fence.group(1).strip();
            if (code.contains("(")) {
                String explanation = (text.substring(0, fence.start()) + " " + text.substring(fence.end())).strip();
                return new Answer(code, explanation);
            }
        }
        return BARE_CODE.matcher(text).matches() ? new Answer(text.strip(), "") : null;
    }

    private static @Nullable Answer json(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(trimmed);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                JsonElement code = object.get("code");
                JsonElement explanation = object.get("explanation");
                JsonElement parallelizable = object.get("parallelizable");
                if (parallelizable != null && parallelizable.isJsonPrimitive() && !parallelizable.getAsBoolean()) {
                    return refusal(explanation != null && explanation.isJsonPrimitive() ? explanation.getAsString() : "");
                }
                if (code != null && code.isJsonPrimitive() && !code.getAsString().isBlank()) {
                    String codeText = code.getAsString().strip();
                    Matcher fence = FENCE.matcher(codeText);
                    return new Answer(fence.find() ? fence.group(1).strip() : codeText,
                            explanation != null && explanation.isJsonPrimitive() ? explanation.getAsString().strip() : "");
                }
            }
        } catch (JsonParseException | IllegalStateException ignored) {
            // not JSON: try a code block
        }
        return null;
    }

    private static Answer refusal(String why) {
        String reason = why.strip();
        return new Answer("", reason, reason.isEmpty() ? "no reason given" : reason);
    }
}
