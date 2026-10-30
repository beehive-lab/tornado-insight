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

/**
 * Hears how an answer streams in, so that the user can see it progress. Called on the thread that
 * reads the stream.
 */
@FunctionalInterface
public interface AnswerProgress {

    /** Ignores the progress. */
    AnswerProgress NONE = (writing, characters) -> { };

    /**
     * @param writing    {@code false} while the model thinks, {@code true} once it writes the answer
     * @param characters the characters of the answer so far
     */
    void streamed(boolean writing, int characters);
}
