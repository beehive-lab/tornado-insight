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

import java.util.function.BooleanSupplier;

/**
 * A language model behind an HTTP API. Calls block, so they run on a background thread.
 */
public interface LlmProvider {

    /**
     * Sends {@code request} and waits for the whole answer.
     *
     * @param cancelled polled while the answer streams in; the call stops with
     *                  {@link LlmException.Kind#CANCELLED} once it returns {@code true}
     * @throws LlmException if the request fails or the model declines it
     */
    @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled) throws LlmException;

    /**
     * {@link #complete(LlmRequest, BooleanSupplier)}, telling {@code progress} how the answer
     * streams in; a provider that cannot tell ignores it.
     */
    default @NotNull LlmResponse complete(@NotNull LlmRequest request, @NotNull BooleanSupplier cancelled,
                                          @NotNull AnswerProgress progress) throws LlmException {
        return complete(request, cancelled);
    }

    /**
     * Checks that the server is reachable and the credentials and model are valid, without
     * generating anything, and reports the models it serves.
     *
     * @param cancelled polled while waiting; the call stops with {@link LlmException.Kind#CANCELLED}
     *                  once it returns {@code true}
     * @throws LlmException if the check fails
     */
    @NotNull ModelInfo describe(@NotNull BooleanSupplier cancelled) throws LlmException;

    /** Whether answers follow the JSON schema of a request; otherwise it is not sent, and answers are text. */
    default boolean followsJsonSchema() {
        return false;
    }

    /**
     * The answer limit, thinking included, that this provider needs, or {@code null} if the user
     * sets it and the context the server reports bounds it.
     */
    default @Nullable Integer answerTokens() {
        return null;
    }
}
