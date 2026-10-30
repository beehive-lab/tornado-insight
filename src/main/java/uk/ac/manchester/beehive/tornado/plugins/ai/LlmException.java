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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * A failed request to a language model. The message is written for the user; {@link #kind()}
 * tells the caller what to do (fix the settings, wait, or report the model's decline).
 */
public final class LlmException extends Exception {

    public enum Kind {
        /** The API key is missing or wrong. */
        AUTHENTICATION,
        /** The key may not do this, or the account has a billing problem. */
        PERMISSION,
        /** The model or endpoint does not exist on this server. */
        NOT_FOUND,
        /** The server rejected the request as malformed. */
        BAD_REQUEST,
        /** The request is too large for the server or the model's context. */
        TOO_LARGE,
        RATE_LIMITED,
        OVERLOADED,
        SERVER_ERROR,
        /** No connection: the server is not running, or the URL or network is wrong. */
        UNREACHABLE,
        TIMEOUT,
        /** The model declined the request (Claude's {@code refusal} stop reason). */
        REFUSED,
        /** The server answered with something that is not the API's format. */
        INVALID_RESPONSE,
        CANCELLED;

        /** Whether the same request may succeed if sent again a little later. */
        public boolean isRetryable() {
            return this == RATE_LIMITED || this == OVERLOADED || this == SERVER_ERROR || this == UNREACHABLE;
        }
    }

    private final Kind kind;
    private final @Nullable Duration retryAfter;

    public LlmException(@NotNull Kind kind, @NotNull String message) {
        this(kind, message, null, null);
    }

    public LlmException(@NotNull Kind kind, @NotNull String message, @Nullable Duration retryAfter,
                        @Nullable Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public @NotNull Kind kind() {
        return kind;
    }

    /** How long the server asked the client to wait before retrying, if it said. */
    public @Nullable Duration retryAfter() {
        return retryAfter;
    }
}
