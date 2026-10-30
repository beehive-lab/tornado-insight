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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One request to a language model.
 *
 * @param system     the instructions that frame the conversation
 * @param messages   the conversation so far, alternating user and assistant, starting and
 *                   ending with the user
 * @param maxTokens  the most tokens the answer may have
 * @param jsonSchema a JSON schema the answer must follow, or {@code null} for free text; a
 *                   provider that cannot enforce it sends the request without it
 */
public record LlmRequest(@NotNull String system, @NotNull List<Message> messages, int maxTokens,
                         @Nullable JsonObject jsonSchema) {

    public LlmRequest {
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.get(messages.size() - 1).role() != Role.USER) {
            throw new IllegalArgumentException("a request must end with a user message");
        }
    }

    /**
     * A request with one user message and a free-text answer.
     */
    public static @NotNull LlmRequest of(@NotNull String system, @NotNull String user, int maxTokens) {
        return new LlmRequest(system, List.of(new Message(Role.USER, user)), maxTokens, null);
    }

    public enum Role {
        USER("user"), ASSISTANT("assistant");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        /** The role's name in both APIs' JSON. */
        public @NotNull String wireName() {
            return wireName;
        }
    }

    public record Message(@NotNull Role role, @NotNull String text) {
    }
}
