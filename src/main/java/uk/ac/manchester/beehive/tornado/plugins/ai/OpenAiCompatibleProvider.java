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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static uk.ac.manchester.beehive.tornado.plugins.ai.JsonFields.integer;
import static uk.ac.manchester.beehive.tornado.plugins.ai.JsonFields.string;

/**
 * A server with the OpenAI chat-completions API, usually local: jitllm, llama.cpp, vLLM, Ollama
 * or LM Studio.
 * <p>
 * Answers are streamed. A JSON schema in the request is not sent, since servers support it
 * unevenly; the caller asks for the format in the prompt instead. Without a configured model the
 * first one {@code /models} lists is used, which suits servers that load a single model.
 * </p>
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

    public static final String DEFAULT_URL = "http://localhost:8090/v1";

    private static final String SERVICE = "the local model server";

    private final URI baseUrl;
    private final @Nullable String apiKey;
    private final @Nullable String model;
    private final HttpStreaming http;
    // The server's first model, once its model list was read, so that it is read once
    private volatile @Nullable String firstModel;

    /**
     * @param baseUrl the API's base URL, including {@code /v1}
     * @param apiKey  the key, or {@code null} or empty for a server that needs none
     * @param model   the model, or {@code null} or empty to use the server's first one
     */
    public OpenAiCompatibleProvider(@NotNull String baseUrl, @Nullable String apiKey, @Nullable String model) {
        this(baseUrl, apiKey, model, Duration.ofMillis(500));
    }

    OpenAiCompatibleProvider(@NotNull String baseUrl, @Nullable String apiKey, @Nullable String model,
                             @NotNull Duration baseRetryDelay) {
        String url = baseUrl.strip();
        this.baseUrl = URI.create(url.endsWith("/") ? url : url + "/");
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
        this.model = model == null || model.isBlank() ? null : model.strip();
        this.http = new HttpStreaming(SERVICE, 2, baseRetryDelay);
    }

    @Override
    public @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled) throws LlmException {
        return complete(request, cancelled, AnswerProgress.NONE);
    }

    @Override
    public @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled,
                                         @NotNull AnswerProgress progress) throws LlmException {
        String served = model != null ? model : firstModel(cancelled);
        Answer answer = new Answer(served, progress);
        http.postStreaming(baseUrl.resolve("chat/completions"), headers(), body(request, served), (event, data) -> {
            if (!data.equals("[DONE]")) {
                answer.onChunk(http.parseObject(data));
            }
        }, cancelled);
        return answer.response();
    }

    @Override
    public @NotNull ModelInfo describe(@NotNull BooleanSupplier cancelled) throws LlmException {
        JsonObject list = http.getJson(baseUrl.resolve("models"), headers(), cancelled);
        List<String> ids = new ArrayList<>();
        Integer context = null;
        if (list.get("data") instanceof JsonArray data) {
            for (JsonElement element : data) {
                if (!(element instanceof JsonObject entry) || string(entry, "id").isEmpty()) {
                    continue;
                }
                String id = string(entry, "id");
                ids.add(id);
                if (id.equals(model) || (model == null && ids.size() == 1)) {
                    // OpenRouter and jitllm say context_length, vLLM max_model_len
                    context = integer(entry, "context_length");
                    if (context == null) {
                        context = integer(entry, "max_model_len");
                    }
                }
            }
        }
        if (ids.isEmpty()) {
            throw new LlmException(Kind.INVALID_RESPONSE, "The model list of " + SERVICE + " is empty");
        }
        firstModel = ids.get(0);
        if (model != null && !ids.contains(model)) {
            throw new LlmException(Kind.NOT_FOUND, "The model '" + model + "' is not served by " + SERVICE
                    + "; it serves " + String.join(", ", ids));
        }
        return new ModelInfo(ids, context);
    }

    private String firstModel(BooleanSupplier cancelled) throws LlmException {
        String first = firstModel;
        return first != null ? first : describe(cancelled).models().get(0);
    }

    JsonObject body(LlmRequest request, String served) {
        JsonObject body = new JsonObject();
        body.addProperty("model", served);
        body.addProperty("max_tokens", request.maxTokens());
        body.addProperty("stream", true);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.system());
        messages.add(system);
        for (LlmRequest.Message message : request.messages()) {
            JsonObject json = new JsonObject();
            json.addProperty("role", message.role().wireName());
            json.addProperty("content", message.text());
            messages.add(json);
        }
        body.add("messages", messages);
        return body;
    }

    private Map<String, String> headers() {
        return apiKey != null ? Map.of("Authorization", "Bearer " + apiKey) : Map.of();
    }

    /** Collects the streamed chunks of one answer. */
    private static final class Answer {
        private final StringBuilder text = new StringBuilder();
        private String model;
        private @Nullable String finishReason;
        private final AnswerProgress progress;

        Answer(String model, AnswerProgress progress) {
            this.model = model;
            this.progress = progress;
        }

        void onChunk(JsonObject chunk) throws LlmException {
            if (chunk.get("error") instanceof JsonObject error) {
                String message = string(error, "message");
                throw new LlmException(Kind.SERVER_ERROR, SERVICE + " failed during the answer"
                        + (message.isEmpty() ? "" : ": " + message));
            }
            if (!string(chunk, "model").isEmpty()) {
                model = string(chunk, "model");
            }
            if (!(chunk.get("choices") instanceof JsonArray choices) || choices.isEmpty()
                    || !(choices.get(0) instanceof JsonObject choice)) {
                return;
            }
            if (choice.get("delta") instanceof JsonObject delta) {
                String content = string(delta, "content");
                text.append(content);
                HttpStreaming.checkAnswerLength(SERVICE, text.length());
                // A thinking model streams its reasoning first, as reasoning (Ollama) or reasoning_content (other servers)
                boolean thinking = content.isEmpty() && (!string(delta, "reasoning").isEmpty() || !string(delta, "reasoning_content").isEmpty());
                if (thinking || !content.isEmpty()) {
                    progress.streamed(!thinking, text.length());
                }
            }
            if (!string(choice, "finish_reason").isEmpty()) {
                finishReason = string(choice, "finish_reason");
            }
        }

        LlmResponse response() throws LlmException {
            if (finishReason == null && text.isEmpty()) {
                throw new LlmException(Kind.INVALID_RESPONSE, SERVICE + " sent no answer");
            }
            // Every chunk stream ends with a finish reason (Ollama, vLLM, llama.cpp, jitllm); without one the
            // server stopped or the connection was cut, and the answer is a part only
            if (finishReason == null) {
                throw new LlmException(Kind.SERVER_ERROR, SERVICE + " stopped sending before the answer was finished");
            }
            return new LlmResponse(text.toString(), model, "length".equals(finishReason), false);
        }
    }
}
