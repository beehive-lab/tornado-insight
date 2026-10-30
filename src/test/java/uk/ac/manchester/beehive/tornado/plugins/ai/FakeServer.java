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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * An in-process HTTP server that answers each request with the next queued reply and records
 * what it received.
 */
final class FakeServer implements AutoCloseable {

    record Reply(int status, Map<String, String> headers, String body, long delayMillis) {
        static Reply json(int status, String body) {
            return new Reply(status, Map.of("Content-Type", "application/json"), body, 0);
        }

        static Reply events(String body) {
            return new Reply(200, Map.of("Content-Type", "text/event-stream"), body, 0);
        }

        /** Reads the request, then closes the connection without answering. */
        static Reply drop() {
            return new Reply(-1, Map.of(), "", 0);
        }
    }

    record Received(String method, String path, Map<String, List<String>> headers, String body) {
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    synchronized FakeServer then(Reply reply) {
        replies.add(reply);
        return this;
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()), body));
        Reply reply;
        synchronized (this) {
            reply = replies.isEmpty() ? Reply.json(500, "{\"error\":{\"message\":\"no reply queued\"}}") : replies.poll();
        }
        if (reply.status() < 0) {
            exchange.close();
            return;
        }
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : 0);
        try (OutputStream out = exchange.getResponseBody()) {
            if (reply.delayMillis() > 0) {
                out.flush();
                Thread.sleep(reply.delayMillis());
            }
            out.write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the client closed the connection (a cancelled request)
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
