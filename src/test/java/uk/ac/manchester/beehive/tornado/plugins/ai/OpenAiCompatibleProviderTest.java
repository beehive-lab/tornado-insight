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

import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.ac.manchester.beehive.tornado.plugins.ai.FakeServer.Reply;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException.Kind;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static uk.ac.manchester.beehive.tornado.plugins.ai.ClaudeProviderTest.expectFailure;

public class OpenAiCompatibleProviderTest {

    // jitllm's model list: one model, with the context it was loaded with
    private static final String JITLLM_MODELS =
            "{\"object\":\"list\",\"data\":[{\"id\":\"gemma-4-E2B-it-Q4_0\",\"object\":\"model\",\"context_length\":131072}]}";

    private FakeServer server;

    @Before
    public void startServer() throws Exception {
        server = new FakeServer();
    }

    @After
    public void stopServer() {
        server.close();
    }

    private static String chunks(String finishReason, String... texts) {
        StringBuilder body = new StringBuilder();
        for (String text : texts) {
            body.append("data: {\"id\":\"c1\",\"model\":\"gemma-4-E2B-it-Q4_0\",\"choices\":[{\"index\":0,\"delta\":{\"content\":")
                    .append(JsonParser.parseString("\"" + text + "\"")).append("},\"finish_reason\":null}]}\n\n");
        }
        body.append("data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"").append(finishReason).append("\"}]}\n\n");
        body.append("data: [DONE]\n\n");
        return body.toString();
    }

    @Test
    public void withoutAModelTheServersFirstModelIsUsed() throws Exception {
        server.then(Reply.json(200, JITLLM_MODELS)).then(Reply.events(chunks("stop", "public static", " void k()")));

        LlmResponse response = new OpenAiCompatibleProvider(server.url("/v1"), null, null, Duration.ZERO)
                .complete(LlmRequest.of("be brief", "convert", 500), () -> false);

        assertEquals("public static void k()", response.text());
        assertEquals("gemma-4-E2B-it-Q4_0", response.model());
        List<FakeServer.Received> received = server.received();
        assertEquals("/v1/models", received.get(0).path());
        assertEquals("/v1/chat/completions", received.get(1).path());
        assertNull(received.get(1).header("Authorization"));
        assertEquals(JsonParser.parseString("""
                {"model":"gemma-4-E2B-it-Q4_0","max_tokens":500,"stream":true,
                 "messages":[{"role":"system","content":"be brief"},{"role":"user","content":"convert"}]}"""),
                JsonParser.parseString(received.get(1).body()));
    }

    @Test
    public void answersAreTextAndTheUserSetsTheLimit() {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(server.url("/v1"), null, null);
        assertFalse(provider.followsJsonSchema());
        assertNull(provider.answerTokens());
    }

    @Test
    public void theModelListIsReadOnce() throws Exception {
        server.then(Reply.json(200, JITLLM_MODELS)).then(Reply.events(chunks("stop", "a"))).then(Reply.events(chunks("stop", "b")));
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(server.url("/v1"), null, null, Duration.ZERO);

        provider.describe(() -> false);
        provider.complete(LlmRequest.of("s", "u", 5), () -> false);
        LlmResponse second = provider.complete(LlmRequest.of("s", "u", 5), () -> false);

        assertEquals("gemma-4-E2B-it-Q4_0", second.model());
        assertEquals(List.of("/v1/models", "/v1/chat/completions", "/v1/chat/completions"),
                server.received().stream().map(FakeServer.Received::path).toList());
    }

    @Test
    public void aConfiguredModelAndKeyAreSent() throws Exception {
        server.then(Reply.events(chunks("length", "cut")));

        LlmResponse response = new OpenAiCompatibleProvider(server.url("/v1/"), "secret", "qwen", Duration.ZERO)
                .complete(LlmRequest.of("s", "u", 5), () -> false);

        assertTrue(response.truncated());
        assertEquals("Bearer secret", server.received().get(0).header("Authorization"));
        assertEquals("qwen", JsonParser.parseString(server.received().get(0).body()).getAsJsonObject().get("model").getAsString());
    }

    @Test
    public void describeReportsTheModelsAndContext() throws Exception {
        server.then(Reply.json(200, JITLLM_MODELS))
                .then(Reply.json(200, "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\",\"max_model_len\":4096}]}"));

        ModelInfo jitllm = new OpenAiCompatibleProvider(server.url("/v1"), null, null).describe(() -> false);
        ModelInfo vllm = new OpenAiCompatibleProvider(server.url("/v1"), null, "b").describe(() -> false);

        assertEquals(List.of("gemma-4-E2B-it-Q4_0"), jitllm.models());
        assertEquals(Integer.valueOf(131072), jitllm.contextLength());
        assertEquals(List.of("a", "b"), vllm.models());
        assertEquals(Integer.valueOf(4096), vllm.contextLength());
    }

    @Test
    public void aModelTheServerDoesNotServeIsNamed() {
        server.then(Reply.json(200, JITLLM_MODELS));

        LlmException e = expectFailure(() -> new OpenAiCompatibleProvider(server.url("/v1"), null, "llama").describe(() -> false));

        assertEquals(Kind.NOT_FOUND, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("gemma-4-E2B-it-Q4_0"));
    }

    @Test
    public void aServerThatIsNotRunningIsReported() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        LlmException e = expectFailure(() -> new OpenAiCompatibleProvider("http://127.0.0.1:" + port + "/v1", null, "m", Duration.ZERO)
                .complete(LlmRequest.of("s", "u", 5), () -> false));

        assertEquals(Kind.UNREACHABLE, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("server is running"));
    }

    @Test
    public void anErrorChunkFails() {
        server.then(Reply.events("data: {\"error\":{\"message\":\"out of memory\"}}\n\n"));

        LlmException e = expectFailure(() -> new OpenAiCompatibleProvider(server.url("/v1"), null, "m", Duration.ZERO)
                .complete(LlmRequest.of("s", "u", 5), () -> false));

        assertEquals(Kind.SERVER_ERROR, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("out of memory"));
    }

    @Test
    public void aStreamThatEndsWithoutAFinishReasonIsNotAnAnswer() {
        // A server that crashed, or a proxy that cut the connection, mid-answer
        server.then(Reply.events(
                "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"public static void \"},\"finish_reason\":null}]}\n\n"));

        LlmException e = expectFailure(() -> new OpenAiCompatibleProvider(server.url("/v1"), null, "m", Duration.ZERO)
                .complete(LlmRequest.of("s", "u", 5), () -> false));

        assertEquals(Kind.SERVER_ERROR, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("stopped sending before the answer was finished"));
    }

    @Test
    public void theProgressSaysWhenTheModelThinksAndWhatItHasWritten() throws Exception {
        // Ollama streams a thinking model's reasoning in reasoning, before the content
        server.then(Reply.events("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\",\"reasoning\":\"hmm\"},\"finish_reason\":null}]}\n\n"
                + chunks("stop", "Hello", ", kernel")));
        java.util.List<String> progress = new java.util.ArrayList<>();

        new OpenAiCompatibleProvider(server.url("/v1"), null, "m", Duration.ZERO).complete(LlmRequest.of("s", "u", 5), () -> false,
                (writing, characters) -> progress.add(writing + " " + characters));

        assertEquals(java.util.List.of("false 0", "true 5", "true 13"), progress);
    }
}
