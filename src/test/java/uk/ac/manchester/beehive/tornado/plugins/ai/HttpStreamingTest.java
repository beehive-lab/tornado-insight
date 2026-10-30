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
import static org.junit.Assert.assertTrue;
import static uk.ac.manchester.beehive.tornado.plugins.ai.ClaudeProviderTest.expectFailure;

public class HttpStreamingTest {

    private FakeServer server;

    @Before
    public void startServer() throws Exception {
        server = new FakeServer();
    }

    @After
    public void stopServer() {
        server.close();
    }

    @Test(timeout = 5000)
    public void aStreamThatStopsSendingTimesOut() {
        // The headers arrive at once, the body only after the idle timeout
        server.then(new Reply(200, Map.of("Content-Type", "text/event-stream"), "data: {}\n\n", 3_000));
        HttpStreaming http = new HttpStreaming("Test server", 0, Duration.ZERO, Duration.ofMillis(300));

        LlmException e = expectFailure(() -> http.postStreaming(URI.create(server.url("/")), Map.of(), new JsonObject(),
                (event, data) -> { }, () -> false));

        assertEquals(Kind.TIMEOUT, e.kind());
    }

    @Test
    public void aRequestTheServerReceivedIsNotSentAgain() {
        // The server may be generating already: a second request would generate (and bill) the answer twice
        server.then(Reply.drop()).then(Reply.events("data: {}\n\n"));
        HttpStreaming http = new HttpStreaming("Test server", 2, Duration.ZERO);

        LlmException e = expectFailure(() -> http.postStreaming(URI.create(server.url("/")), Map.of(), new JsonObject(),
                (event, data) -> { }, () -> false));

        assertEquals(Kind.UNREACHABLE, e.kind());
        assertEquals(1, server.received().size());
    }

    @Test
    public void aGetIsSentAgainAfterADroppedConnection() throws Exception {
        server.then(Reply.drop()).then(Reply.json(200, "{\"id\":\"m\"}"));
        HttpStreaming http = new HttpStreaming("Test server", 2, Duration.ZERO);

        JsonObject answer = http.getJson(URI.create(server.url("/")), Map.of(), () -> false);

        assertEquals("m", answer.get("id").getAsString());
        assertEquals(2, server.received().size());
    }

    @Test
    public void eventsAreSplitAtEveryLineEndTheFormatAllows() throws Exception {
        server.then(Reply.events("event: a\r\ndata: 1\r\n\r\ndata: 2\r\rdata: 3\n\n: comment\ndata: 4\ndata: 5\n\n"));
        HttpStreaming http = new HttpStreaming("Test server", 0, Duration.ZERO);
        List<String> events = new ArrayList<>();

        http.postStreaming(URI.create(server.url("/")), Map.of(), new JsonObject(),
                (event, data) -> events.add(event + "=" + data), () -> false);

        assertEquals(List.of("a=1", "null=2", "null=3", "null=4\n5"), events);
    }

    // A broken or hostile server cannot fill the IDE's memory
    @Test
    public void aStreamWithAnOverlongLineFails() {
        server.then(Reply.events("data: " + "x".repeat(HttpStreaming.MAX_EVENT_CHARS) + "\n\n"));
        HttpStreaming http = new HttpStreaming("Test server", 0, Duration.ZERO);

        LlmException e = expectFailure(() -> http.postStreaming(URI.create(server.url("/")), Map.of(), new JsonObject(),
                (event, data) -> { }, () -> false));

        assertEquals(Kind.INVALID_RESPONSE, e.kind());
        assertTrue(e.getMessage(), e.getMessage().contains("a line or an event of over 4M characters"));
    }

    @Test
    public void anOverlongJsonBodyFails() {
        server.then(Reply.json(200, "{\"id\":\"" + "x".repeat(HttpStreaming.MAX_BODY_BYTES) + "\"}"));
        HttpStreaming http = new HttpStreaming("Test server", 0, Duration.ZERO);

        LlmException e = expectFailure(() -> http.getJson(URI.create(server.url("/")), Map.of(), () -> false));

        assertEquals(Kind.INVALID_RESPONSE, e.kind());
    }

    @Test
    public void anOverlongAnswerFails() throws Exception {
        HttpStreaming.checkAnswerLength("Test server", HttpStreaming.MAX_ANSWER_CHARS);
        LlmException e = expectFailure(() -> HttpStreaming.checkAnswerLength("Test server", HttpStreaming.MAX_ANSWER_CHARS + 1));
        assertEquals(Kind.INVALID_RESPONSE, e.kind());
    }
}
