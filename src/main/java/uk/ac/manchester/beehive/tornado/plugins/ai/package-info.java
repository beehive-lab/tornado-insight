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

/**
 * Language-model providers for the AI-assisted transformation of Java methods into TornadoVM kernels.
 * <p>
 * {@link LlmProvider} sends one {@link LlmRequest} and returns the model's {@link LlmResponse}, or
 * fails with an {@link LlmException} that says what went wrong in terms a user can act on.
 * {@link ClaudeProvider} calls the Claude API and {@link OpenAiCompatibleProvider} any server with
 * the OpenAI chat-completions API (jitllm, llama.cpp, vLLM, Ollama, LM Studio). Both talk plain HTTP
 * through {@code HttpStreaming}, so the plugin bundles no client library. This package uses no other
 * plugin package.
 * </p>
 */
package uk.ac.manchester.beehive.tornado.plugins.ai;
