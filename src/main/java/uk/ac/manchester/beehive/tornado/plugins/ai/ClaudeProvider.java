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

package uk.ac.manchester.beehive.tornado.plugins.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException.Kind;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static uk.ac.manchester.beehive.tornado.plugins.ai.JsonFields.integer;
import static uk.ac.manchester.beehive.tornado.plugins.ai.JsonFields.string;

/**
 * The Claude API's Messages endpoint, over plain HTTP.
 * <p>
 * Requests stream the answer and leave thinking at the model's default (adaptive on current
 * models), with depth set by {@link Effort}. For the models that have one, they opt into the
 * server-side fallback: if the model declines, the API re-runs the request on the model Anthropic
 * recommends for that kind of decline. A decline mid-answer keeps the partial text, which the
 * fallback model continues, so the answer is the text of every text block. A decline that remains
 * (the {@code refusal} stop reason) fails with {@link Kind#REFUSED}, and its partial answer is
 * discarded.
 * </p>
 */
public final class ClaudeProvider implements LlmProvider {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";
    /** The answer limit: thinking counts towards it, and at high effort it is long. */
    public static final int MAX_ANSWER_TOKENS = 64_000;

    private static final URI API = URI.create("https://api.anthropic.com/");
    private static final String API_VERSION = "2026-10-21";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    // The models with a server-side fallback (platform.claude.com/docs/en/build-with-claude/refusals-and-fallback);
    // Claude Haiku 5.5 has none, and an older model may reject the parameter
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-fable-5-1", "claude-mythos-5-1", "claude-fable-5",
            "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");
    private static final String SERVICE = "Claude API";

    /** How much the model thinks before answering. */
    public enum Effort {
        LOW, MEDIUM, HIGH, XHIGH, MAX;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final URI baseUrl;
    private final String apiKey;
    private final String model;
    private final Effort effort;
    private final HttpStreaming http;

    public ClaudeProvider(@NotNull String apiKey, @NotNull String model, @NotNull Effort effort) {
        this(API, apiKey, model, effort, Duration.ofMillis(500));
    }

    ClaudeProvider(@NotNull URI baseUrl, @NotNull String apiKey, @NotNull String model, @NotNull Effort effort,
                   @NotNull Duration baseRetryDelay) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.effort = effort;
        this.http = new HttpStreaming(SERVICE, 2, baseRetryDelay);
    }

    @Override
    public @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled) throws LlmException {
        return complete(request, cancelled, AnswerProgress.NONE);
    }

    @Override
    public @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled,
                                         @NotNull AnswerProgress progress) throws LlmException {
        Answer answer = new Answer(model, progress);
        http.postStreaming(baseUrl.resolve("v1/messages"), headers(fallback()), body(request),
                (event, data) -> answer.onEvent(http.parseObject(data)), cancelled);
        return answer.response();
    }

    @Override
    public @NotNull ModelInfo describe(@NotNull BooleanSupplier cancelled) throws LlmException {
        // Checks the key and the model without generating anything
        URI uri;
        try {
            uri = baseUrl.resolve("v1/models/" + model);
        } catch (IllegalArgumentException e) {
            throw new LlmException(Kind.BAD_REQUEST, "'" + model + "' is not a valid model name", null, e);
        }
        JsonObject info = http.getJson(uri, headers(false), cancelled);
        String id = string(info, "id");
        return new ModelInfo(List.of(id.isEmpty() ? model : id), integer(info, "max_input_tokens"));
    }

    @Override
    public boolean followsJsonSchema() {
        return true;
    }

    @Override
    public @NotNull Integer answerTokens() {
        return MAX_ANSWER_TOKENS;
    }

    JsonObject body(LlmRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", request.maxTokens());
        body.addProperty("stream", true);
        if (fallback()) {
            body.addProperty("fallbacks", "default");
        }
        body.addProperty("system", request.system());
        JsonArray messages = new JsonArray();
        for (LlmRequest.Message message : request.messages()) {
            JsonObject json = new JsonObject();
            json.addProperty("role", message.role().wireName());
            json.addProperty("content", message.text());
            messages.add(json);
        }
        body.add("messages", messages);
        JsonObject outputConfig = new JsonObject();
        outputConfig.addProperty("effort", effort.wireName());
        if (request.jsonSchema() != null) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_schema");
            format.add("schema", request.jsonSchema());
            outputConfig.add("format", format);
        }
        body.add("output_config", outputConfig);
        return body;
    }

    // Whether the request opts into the server-side fallback
    private boolean fallback() {
        return FALLBACK_MODELS.contains(model);
    }

    private Map<String, String> headers(boolean withFallback) {
        return withFallback
                ? Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION, "anthropic-beta", FALLBACK_BETA)
                : Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION);
    }

    /** Collects the streamed events of one answer. */
    private static final class Answer {
        private final StringBuilder text = new StringBuilder();
        private String model;
        private boolean fellBack;
        private @Nullable String stopReason;
        private @Nullable String refusalCategory;
        private final Set<Integer> textBlocks = new HashSet<>();
        private final AnswerProgress progress;

        Answer(String requestedModel, AnswerProgress progress) {
            this.model = requestedModel;
            this.progress = progress;
        }

        void onEvent(JsonObject event) throws LlmException {
            switch (string(event, "type")) {
                case "message_start" -> {
                    JsonObject message = event.getAsJsonObject("message");
                    String served = message != null ? string(message, "model") : "";
                    if (!served.isEmpty()) {
                        // The fallback model's, if the decline came before any output; an alias may come back resolved
                        model = served;
                    }
                    if (message != null && message.get("usage") instanceof JsonObject usage) {
                        fellBack |= servedByFallback(usage);
                    }
                }
                case "content_block_start" -> {
                    JsonObject block = event.getAsJsonObject("content_block");
                    String type = block != null ? string(block, "type") : "";
                    if (type.equals("text")) {
                        textBlocks.add(integer(event, "index"));
                        progress.streamed(true, text.length());
                    } else if (type.equals("thinking") || type.equals("redacted_thinking")) {
                        progress.streamed(false, text.length());
                    } else if (type.equals("fallback")) {
                        // Marks where the fallback model took over; the text before it is kept, and continued
                        fellBack = true;
                        if (block.get("to") instanceof JsonObject to && !string(to, "model").isEmpty()) {
                            model = string(to, "model");
                        }
                    }
                }
                case "content_block_delta" -> {
                    JsonObject delta = event.getAsJsonObject("delta");
                    if (delta != null && string(delta, "type").equals("text_delta")
                            && textBlocks.contains(integer(event, "index"))) {
                        text.append(string(delta, "text"));
                        HttpStreaming.checkAnswerLength(SERVICE, text.length());
                        progress.streamed(true, text.length());
                    }
                }
                case "message_delta" -> {
                    if (event.get("usage") instanceof JsonObject usage) {
                        // A conversation that fell back earlier is served by the fallback model directly, with no fallback block
                        fellBack |= servedByFallback(usage);
                    }
                    JsonObject delta = event.getAsJsonObject("delta");
                    if (delta != null && !string(delta, "stop_reason").isEmpty()) {
                        stopReason = string(delta, "stop_reason");
                        if (delta.get("stop_details") instanceof JsonObject details) {
                            refusalCategory = string(details, "category");
                        }
                    }
                }
                case "error" -> {
                    JsonObject error = event.getAsJsonObject("error");
                    String type = error != null ? string(error, "type") : "";
                    String message = error != null ? string(error, "message") : "";
                    throw new LlmException(errorKind(type), SERVICE + " error during the answer"
                            + (message.isEmpty() ? "" : ": " + message));
                }
                default -> {
                    // ping, content_block_stop, message_stop
                }
            }
        }

        LlmResponse response() throws LlmException {
            if ("refusal".equals(stopReason)) {
                throw new LlmException(Kind.REFUSED, "Claude declined this request"
                        + (refusalCategory == null || refusalCategory.isEmpty() ? "" : " (" + refusalCategory + ")") + ".");
            }
            if (stopReason == null) {
                throw new LlmException(Kind.INVALID_RESPONSE, SERVICE + " ended the answer without a stop reason");
            }
            return new LlmResponse(text.toString(), model, "max_tokens".equals(stopReason), fellBack);
        }
    }

    // A fallback_message attempt in usage.iterations is the fallback model's
    private static boolean servedByFallback(JsonObject usage) {
        if (usage.get("iterations") instanceof JsonArray iterations) {
            for (JsonElement iteration : iterations) {
                if (iteration instanceof JsonObject attempt && string(attempt, "type").equals("fallback_message")) {
                    return true;
                }
            }
        }
        return false;
    }

    // The error types of the Claude API's error events
    private static Kind errorKind(String type) {
        return switch (type) {
            case "authentication_error" -> Kind.AUTHENTICATION;
            case "permission_error", "billing_error" -> Kind.PERMISSION;
            case "not_found_error" -> Kind.NOT_FOUND;
            case "request_too_large" -> Kind.TOO_LARGE;
            case "rate_limit_error" -> Kind.RATE_LIMITED;
            case "overloaded_error" -> Kind.OVERLOADED;
            case "invalid_request_error" -> Kind.BAD_REQUEST;
            default -> Kind.SERVER_ERROR;
        };
    }
}
