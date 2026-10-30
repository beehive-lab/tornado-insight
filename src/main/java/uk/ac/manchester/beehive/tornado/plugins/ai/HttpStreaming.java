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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.ai.LlmException.Kind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The HTTP both providers share: JSON requests, server-sent-event streams, retries of transient
 * failures, and cancellation.
 * <p>
 * A request that fails before any of its answer arrives is retried up to {@code maxRetries} times
 * when the failure is transient ({@link Kind#isRetryable()}), after the delay the server asked
 * for or an exponential backoff. A stream is read on a thread of its own while the caller polls the
 * cancellation flag, since a blocking read cannot be interrupted; cancelling closes the connection,
 * which also stops the server generating. A stream that sends nothing for {@code idleTimeout}
 * fails with {@link Kind#TIMEOUT}.
 * </p>
 */
final class HttpStreaming {

    /** Receives one server-sent event. */
    interface EventListener {
        /**
         * @param event the {@code event:} field, or {@code null} if the event had none
         * @param data  the {@code data:} field
         */
        void onEvent(@Nullable String event, @NotNull String data) throws LlmException;
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Until the response headers arrive; a local server may only send them after the prompt is read
    private static final Duration HEADERS_TIMEOUT = Duration.ofMinutes(5);
    // Claude sends a ping every few seconds, and a local server a token at a time
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);
    // A broken or hostile server could otherwise fill the IDE's memory: a JSON or error body, and
    // one line or event of a stream, are read up to these sizes
    static final int MAX_BODY_BYTES = 4 << 20;
    static final int MAX_EVENT_CHARS = 4 << 20;
    /**
     * The longest answer a provider accepts, in characters: about four times what 64K output
     * tokens make. The providers check it as the answer streams in.
     */
    static final int MAX_ANSWER_CHARS = 1 << 20;

    /** Fails once an answer is longer than {@link #MAX_ANSWER_CHARS}. */
    static void checkAnswerLength(@NotNull String service, int length) throws LlmException {
        if (length > MAX_ANSWER_CHARS) {
            throw new LlmException(Kind.INVALID_RESPONSE, service + " sent an answer of over "
                    + MAX_ANSWER_CHARS / (1 << 20) + "M characters, more than any kernel needs");
        }
    }
    private static final long POLL_MILLIS = 100;
    // A stream is read for as long as the answer takes, so not on a shared pool, and on a thread of
    // its own that ends with the stream: no idle thread keeps the plugin from being unloaded
    private static final Executor READER = task -> {
        Thread thread = new Thread(task, "TornadoInsight AI stream reader");
        thread.setDaemon(true);
        thread.start();
    };

    private final HttpClient client;
    private final String service;
    private final int maxRetries;
    private final Duration baseRetryDelay;
    private final Duration idleTimeout;

    /**
     * @param service        the server's name in error messages, e.g. "Claude API"
     * @param maxRetries     how often a transient failure is retried
     * @param baseRetryDelay the first backoff delay; it doubles with each retry
     */
    HttpStreaming(@NotNull String service, int maxRetries, @NotNull Duration baseRetryDelay) {
        this(service, maxRetries, baseRetryDelay, IDLE_TIMEOUT);
    }

    HttpStreaming(@NotNull String service, int maxRetries, @NotNull Duration baseRetryDelay, @NotNull Duration idleTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.service = service;
        this.maxRetries = maxRetries;
        this.baseRetryDelay = baseRetryDelay;
        this.idleTimeout = idleTimeout;
    }

    /**
     * Sends a GET and returns its JSON body.
     */
    @NotNull JsonObject getJson(@NotNull URI uri, @NotNull Map<String, String> headers,
                                @NotNull BooleanSupplier cancelled) throws LlmException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(HEADERS_TIMEOUT).GET();
        headers.forEach(request::header);
        HttpResponse<InputStream> response = sendWithRetries(request.build(), cancelled);
        String text;
        try (InputStream body = response.body()) {
            text = read(body);
        } catch (IOException e) {
            throw connectionFailure(e);
        }
        if (text == null) {
            throw new LlmException(Kind.INVALID_RESPONSE, service + " sent more than " + MAX_BODY_BYTES / (1 << 20) + " MB");
        }
        return parseObject(text);
    }

    // The body as text, or null if it is longer than MAX_BODY_BYTES
    private static @Nullable String read(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * POSTs {@code body} and passes each server-sent event of the answer to {@code listener},
     * until the stream ends or the listener throws.
     */
    void postStreaming(@NotNull URI uri, @NotNull Map<String, String> headers, @NotNull JsonObject body,
                       @NotNull EventListener listener, @NotNull BooleanSupplier cancelled) throws LlmException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(HEADERS_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        headers.forEach(request::header);
        HttpResponse<InputStream> response = sendWithRetries(request.build(), cancelled);
        InputStream stream = response.body();
        AtomicLong lastRead = new AtomicLong(System.nanoTime());
        CompletableFuture<Void> reading = CompletableFuture.runAsync(() -> readEvents(stream, listener, lastRead), READER);
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    throw cancelledException();
                }
                if (System.nanoTime() - lastRead.get() > idleTimeout.toNanos()) {
                    throw new LlmException(Kind.TIMEOUT, service + " stopped sending its answer");
                }
                try {
                    reading.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException e) {
                    // still reading; poll again
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw cancelledException();
                } catch (ExecutionException e) {
                    throw unwrap(e.getCause());
                }
            }
        } finally {
            close(stream);
        }
    }

    // Splits the stream into events: "event:" and "data:" lines, ended by a blank line
    private void readEvents(InputStream stream, EventListener listener, AtomicLong lastRead) {
        try (LineReader lines = new LineReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = lines.next()) != null) {
                lastRead.set(System.nanoTime());
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        listener.onEvent(event, data.toString());
                    }
                    event = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).strip());
                    if (data.length() > MAX_EVENT_CHARS) {
                        throw tooLong();
                    }
                }
                // other fields (id:, retry:) and ":" comments are not used
            }
            if (!data.isEmpty()) {
                listener.onEvent(event, data.toString());
            }
        } catch (LlmException e) {
            throw new WrappedLlmException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private LlmException tooLong() {
        return new LlmException(Kind.INVALID_RESPONSE, service + " sent a line or an event of over "
                + MAX_EVENT_CHARS / (1 << 20) + "M characters");
    }

    /**
     * The lines of a stream, ended by {@code \n}, {@code \r\n} or {@code \r} as server-sent events
     * allow, each at most {@link #MAX_EVENT_CHARS} long. Unlike BufferedReader.lines(), it does not
     * read a line of any length into memory.
     */
    private final class LineReader implements AutoCloseable {
        private final BufferedReader reader;
        private final StringBuilder line = new StringBuilder();
        // After a \r, a \n that follows belongs to the same line end; it is skipped when it comes
        private boolean skipNewline;

        LineReader(InputStreamReader reader) {
            this.reader = new BufferedReader(reader);
        }

        // The next line without its end, or null at the end of the stream
        @Nullable String next() throws IOException, LlmException {
            line.setLength(0);
            int c;
            while ((c = reader.read()) != -1) {
                boolean skip = skipNewline && c == '\n';
                skipNewline = false;
                if (skip) {
                    continue;
                }
                if (c == '\n' || c == '\r') {
                    skipNewline = c == '\r';
                    return line.toString();
                }
                if (line.length() >= MAX_EVENT_CHARS) {
                    throw tooLong();
                }
                line.append((char) c);
            }
            return line.isEmpty() ? null : line.toString();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private HttpResponse<InputStream> sendWithRetries(HttpRequest request, BooleanSupplier cancelled) throws LlmException {
        for (int attempt = 0; ; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw cancelledException();
            }
            LlmException failure;
            try {
                HttpResponse<InputStream> response = sendCancellable(request, cancelled);
                if (response.statusCode() / 100 == 2) {
                    return response;
                }
                failure = httpError(response);
            } catch (LlmException e) {
                failure = e;
            }
            if (!failure.kind().isRetryable() || attempt >= maxRetries || sentPost(request, failure)) {
                throw failure;
            }
            Duration delay = failure.retryAfter() != null
                    ? failure.retryAfter()
                    : baseRetryDelay.multipliedBy(1L << attempt);
            if (delay.compareTo(MAX_RETRY_DELAY) > 0) {
                throw failure;
            }
            sleep(delay, cancelled);
        }
    }

    private HttpResponse<InputStream> sendCancellable(HttpRequest request, BooleanSupplier cancelled) throws LlmException {
        CompletableFuture<HttpResponse<InputStream>> sending =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        while (true) {
            if (cancelled.getAsBoolean()) {
                sending.cancel(true);
                throw cancelledException();
            }
            try {
                return sending.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // waiting for the response headers; poll again
            } catch (InterruptedException e) {
                sending.cancel(true);
                Thread.currentThread().interrupt();
                throw cancelledException();
            } catch (ExecutionException e) {
                throw unwrap(e.getCause());
            }
        }
    }

    // A POST whose connection failed after it was sent may be generating already; sent again, the
    // answer would be generated (and billed) twice. Only one that never connected is retried
    private static boolean sentPost(HttpRequest request, LlmException failure) {
        return request.method().equals("POST") && failure.kind() == Kind.UNREACHABLE
                && !(failure.getCause() instanceof HttpConnectTimeoutException || failure.getCause() instanceof ConnectException);
    }

    private void sleep(Duration delay, BooleanSupplier cancelled) throws LlmException {
        long end = System.nanoTime() + delay.toNanos();
        while (System.nanoTime() < end) {
            if (cancelled.getAsBoolean()) {
                throw cancelledException();
            }
            try {
                Thread.sleep(Math.min(POLL_MILLIS, Math.max(1, (end - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw cancelledException();
            }
        }
    }

    /**
     * The failure an HTTP error status stands for. Both APIs put a message in
     * {@code {"error": {"message": ...}}}.
     */
    private LlmException httpError(HttpResponse<InputStream> response) {
        String body;
        // Only its start is shown; the rest is left unread
        try (InputStream in = response.body()) {
            body = new String(in.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            body = "";
        }
        String detail = errorMessage(body);
        int status = response.statusCode();
        Kind kind = switch (status) {
            case 400 -> Kind.BAD_REQUEST;
            case 401 -> Kind.AUTHENTICATION;
            case 402, 403 -> Kind.PERMISSION;
            case 404 -> Kind.NOT_FOUND;
            case 408 -> Kind.TIMEOUT;
            case 413 -> Kind.TOO_LARGE;
            case 429 -> Kind.RATE_LIMITED;
            case 503, 529 -> Kind.OVERLOADED;
            default -> status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_REQUEST;
        };
        String message = service + " returned HTTP " + status + (detail != null ? ": " + detail : "");
        return new LlmException(kind, message, retryAfter(response), null);
    }

    /** The {@code error.message} of an error body, or {@code null} if it has none. */
    static @Nullable String errorMessage(@NotNull String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().get("error") instanceof JsonObject error
                    && error.get("message") != null && error.get("message").isJsonPrimitive()) {
                return error.get("message").getAsString();
            }
        } catch (JsonParseException | IllegalStateException e) {
            // not JSON; fall through
        }
        String trimmed = body.strip();
        return trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(200, trimmed.length()));
    }

    private static @Nullable Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(value -> {
            try {
                return Duration.ofSeconds(Long.parseLong(value.strip()));
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    @NotNull JsonObject parseObject(@NotNull String text) throws LlmException {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            throw new LlmException(Kind.INVALID_RESPONSE, service + " sent malformed JSON", null, e);
        }
        throw new LlmException(Kind.INVALID_RESPONSE, service + " sent JSON that is not an object");
    }

    private LlmException unwrap(Throwable cause) {
        if (cause instanceof WrappedLlmException wrapped) {
            return wrapped.exception;
        }
        if (cause instanceof UncheckedIOException io) {
            return connectionFailure(io.getCause());
        }
        if (cause instanceof IOException io) {
            return connectionFailure(io);
        }
        return new LlmException(Kind.INVALID_RESPONSE, service + ": " + cause, null, cause);
    }

    private LlmException connectionFailure(IOException e) {
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException) {
            return new LlmException(Kind.UNREACHABLE, "Cannot connect to " + service
                    + ". Check that the server is running and the URL is right.", null, e);
        }
        if (e instanceof HttpTimeoutException) {
            return new LlmException(Kind.TIMEOUT, service + " did not answer in time", null, e);
        }
        return new LlmException(Kind.UNREACHABLE, "The connection to " + service + " failed: " + e.getMessage(), null, e);
    }

    private LlmException cancelledException() {
        return new LlmException(Kind.CANCELLED, "Cancelled");
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // closing only stops the server early
        }
    }

    // Carries an LlmException out of the reading thread
    private static final class WrappedLlmException extends RuntimeException {
        final LlmException exception;

        WrappedLlmException(LlmException exception) {
            super(exception);
            this.exception = exception;
        }
    }
}
