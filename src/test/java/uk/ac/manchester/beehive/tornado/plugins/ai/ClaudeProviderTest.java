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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.ac.manchester.beehive.tornado.plugins.ai.FakeServer.Reply;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException.Kind;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ClaudeProviderTest {

    private static final String MODEL = "claude-opus-5-5";

    private FakeServer server;

    @Before
    public void startServer() throws Exception {
        server = new FakeServer();
    }

    @After
    public void stopServer() {
        server.close();
    }

    private ClaudeProvider provider() {
        return new ClaudeProvider(URI.create(server.url("/")), "sk-test", MODEL, ClaudeProvider.Effort.HIGH, Duration.ZERO);
    }

    static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    static String answer(String model, String stopReason, String... texts) {
        StringBuilder body = new StringBuilder(event("message_start",
                "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"" + model + "\"}}"));
        body.append(event("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        body.append(event("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}"));
        body.append(event("content_block_start",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        for (String text : texts) {
            body.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,"
                    + "\"delta\":{\"type\":\"text_delta\",\"text\":" + JsonParser.parseString("\"" + text + "\"") + "}}"));
        }
        body.append(event("ping", "{\"type\":\"ping\"}"));
        body.append(event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\"}}"));
        body.append(event("message_stop", "{\"type\":\"message_stop\"}"));
        return body.toString();
    }

    @Test
    public void followsSchemasAndNeedsRoomToThink() {
        assertTrue(provider().followsJsonSchema());
        assertEquals(Integer.valueOf(ClaudeProvider.MAX_ANSWER_TOKENS), provider().answerTokens());
    }

    @Test
    public void streamsTheAnswerAndSendsTheAgreedRequest() throws Exception {
        server.then(Reply.events(answer(MODEL, "end_turn", "Hello", ", kernel")));
        JsonObject schema = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();

        LlmResponse response = provider().complete(
                new LlmRequest("be brief", java.util.List.of(new LlmRequest.Message(LlmRequest.Role.USER, "hi")), 1000, schema),
                () -> false);

        assertEquals("Hello, kernel", response.text());
        assertEquals(MODEL, response.model());
        assertFalse(response.truncated());
        assertFalse(response.fellBack());

        FakeServer.Received request = server.received().get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/messages", request.path());
        assertEquals("sk-test", request.header("x-api-key"));
        assertEquals("2026-10-21", request.header("anthropic-version"));
        assertEquals("server-side-fallback-2026-07-01", request.header("anthropic-beta"));
        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals(JsonParser.parseString("""
                {"model":"claude-opus-5-5","max_tokens":1000,"stream":true,"fallbacks":"default","system":"be brief",
                 "messages":[{"role":"user","content":"hi"}],
                 "output_config":{"effort":"high","format":{"type":"json_schema","schema":{"type":"object"}}}}"""), body);
    }

    @Test
    public void theTokenLimitMarksTheAnswerTruncated() throws Exception {
        server.then(Reply.events(answer(MODEL, "max_tokens", "partial")));

        LlmResponse response = provider().complete(LlmRequest.of("s", "u", 10), () -> false);

        assertTrue(response.truncated());
        assertEquals("partial", response.text());
        assertFalse(JsonParser.parseString(server.received().get(0).body()).getAsJsonObject()
                .getAsJsonObject("output_config").has("format"));
    }

    @Test
    public void aRefusalFailsAndDiscardsThePartialAnswer() {
        String body = answer(MODEL, "end_turn", "partial").replace(
                "{\"stop_reason\":\"end_turn\"}", "{\"stop_reason\":\"refusal\",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\"}}");
        server.then(Reply.events(body));

        LlmException e = expectFailure(() -> provider().complete(LlmRequest.of("s", "u", 10), () -> false));

        assertEquals(Kind.REFUSED, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("cyber"));
    }

    @Test
    public void aServerSideFallbackIsReported() throws Exception {
        String body = answer("claude-opus-4-8", "end_turn", "rescued").replace(
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"fallback\"}}");
        server.then(Reply.events(body));

        LlmResponse response = provider().complete(LlmRequest.of("s", "u", 10), () -> false);

        assertTrue(response.fellBack());
        assertEquals("claude-opus-4-8", response.model());
        assertEquals("rescued", response.text());
    }

    @Test
    public void aFallbackMidAnswerContinuesThePartialText() throws Exception {
        // message_start names the requested model; the fallback block, the model that continues
        String body = answer(MODEL, "end_turn", "public static void ").replace(
                event("ping", "{\"type\":\"ping\"}"),
                event("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":"
                        + "{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5-5\"},\"to\":{\"model\":\"claude-opus-5\"}}}")
                        + event("content_block_start", "{\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")
                        + event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":3,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"saxpy() { }\"}}"));
        server.then(Reply.events(body));

        LlmResponse response = provider().complete(LlmRequest.of("s", "u", 10), () -> false);

        assertTrue(response.fellBack());
        assertEquals("claude-opus-5", response.model());
        assertEquals("public static void saxpy() { }", response.text());
    }

    @Test
    public void aModelNamedOtherwiseInTheAnswerIsNotAFallback() throws Exception {
        // An alias may come back as the model's resolved id
        server.then(Reply.events(answer("claude-opus-5-5-20261001", "end_turn", "ok")));

        LlmResponse response = provider().complete(LlmRequest.of("s", "u", 10), () -> false);

        assertFalse(response.fellBack());
        assertEquals("claude-opus-5-5-20261001", response.model());
    }

    @Test
    public void aConversationServedByTheFallbackModelIsReported() throws Exception {
        // Sticky routing: no fallback block, only the fallback_message attempt in usage.iterations
        server.then(Reply.events(answer("claude-opus-5", "end_turn", "ok").replace(
                "\"delta\":{\"stop_reason\":\"end_turn\"}}",
                "\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"iterations\":[{\"type\":\"fallback_message\",\"model\":\"claude-opus-5\"}]}}")));

        LlmResponse response = provider().complete(LlmRequest.of("s", "u", 10), () -> false);

        assertTrue(response.fellBack());
    }

    @Test
    public void aModelWithoutAServerSideFallbackIsNotAskedForOne() throws Exception {
        server.then(Reply.events(answer("claude-haiku-5-5", "end_turn", "ok")));

        new ClaudeProvider(URI.create(server.url("/")), "sk-test", "claude-haiku-5-5", ClaudeProvider.Effort.HIGH, Duration.ZERO)
                .complete(LlmRequest.of("s", "u", 10), () -> false);

        FakeServer.Received request = server.received().get(0);
        assertNull(request.header("anthropic-beta"));
        assertFalse(request.body(), JsonParser.parseString(request.body()).getAsJsonObject().has("fallbacks"));
    }

    @Test
    public void aWrongKeyIsNotRetried() {
        server.then(Reply.json(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));

        LlmException e = expectFailure(() -> provider().complete(LlmRequest.of("s", "u", 10), () -> false));

        assertEquals(Kind.AUTHENTICATION, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("invalid x-api-key"));
        assertEquals(1, server.received().size());
    }

    @Test
    public void anOverloadedServerIsRetried() throws Exception {
        server.then(Reply.json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))
                .then(Reply.events(answer(MODEL, "end_turn", "ok")));

        assertEquals("ok", provider().complete(LlmRequest.of("s", "u", 10), () -> false).text());
        assertEquals(2, server.received().size());
    }

    @Test
    public void aLongRetryAfterIsNotWaitedFor() {
        server.then(new Reply(429, Map.of("retry-after", "120"),
                "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}", 0));

        LlmException e = expectFailure(() -> provider().complete(LlmRequest.of("s", "u", 10), () -> false));

        assertEquals(Kind.RATE_LIMITED, e.kind());
        assertEquals(Duration.ofSeconds(120), e.retryAfter());
        assertEquals(1, server.received().size());
    }

    @Test
    public void anErrorEventDuringTheAnswerFails() {
        server.then(Reply.events(event("message_start", "{\"type\":\"message_start\",\"message\":{\"model\":\"" + MODEL + "\"}}")
                + event("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")));

        LlmException e = expectFailure(() -> provider().complete(LlmRequest.of("s", "u", 10), () -> false));

        assertEquals(Kind.OVERLOADED, e.kind());
    }

    @Test
    public void describeChecksTheModelWithoutGenerating() throws Exception {
        server.then(Reply.json(200, "{\"id\":\"claude-opus-5-5\",\"max_input_tokens\":1000000,\"max_tokens\":128000}"));

        ModelInfo info = provider().describe(() -> false);

        assertEquals(java.util.List.of(MODEL), info.models());
        assertEquals(Integer.valueOf(1_000_000), info.contextLength());
        FakeServer.Received request = server.received().get(0);
        assertEquals("GET", request.method());
        assertEquals("/v1/models/claude-opus-5-5", request.path());
        assertNull(request.header("anthropic-beta"));
    }

    @Test(timeout = 5000)
    public void cancellingStopsAWaitingAnswer() {
        server.then(new Reply(200, Map.of("Content-Type", "text/event-stream"), answer(MODEL, "end_turn", "late"), 10_000));
        long start = System.nanoTime();

        LlmException e = expectFailure(() -> provider().complete(LlmRequest.of("s", "u", 10),
                () -> System.nanoTime() - start > 300_000_000L));

        assertEquals(Kind.CANCELLED, e.kind());
    }

    interface Call {
        void run() throws LlmException;
    }

    static LlmException expectFailure(Call call) {
        try {
            call.run();
        } catch (LlmException e) {
            return e;
        }
        fail("expected an LlmException");
        return null;
    }

    @Test
    public void theProgressSaysWhenTheModelThinksAndWhatItHasWritten() throws Exception {
        server.then(Reply.events(answer(MODEL, "end_turn", "Hello", ", kernel")));
        List<String> progress = new ArrayList<>();

        provider().complete(LlmRequest.of("s", "u", 10), () -> false,
                (writing, characters) -> progress.add(writing + " " + characters));

        assertEquals(List.of("false 0", "true 0", "true 5", "true 13"), progress);
    }
}
