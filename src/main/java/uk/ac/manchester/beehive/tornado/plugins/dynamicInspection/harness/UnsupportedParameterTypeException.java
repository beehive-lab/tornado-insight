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

package uk.ac.manchester.beehive.tornado.plugins.dynamicInspection.harness;

import org.jetbrains.annotations.NotNull;

/**
 * Thrown when the harness generator has no way to create test data for a kernel
 * parameter of some type.
 */
public final class UnsupportedParameterTypeException extends Exception {

    private final String parameterName;
    private final String type;

    public UnsupportedParameterTypeException(@NotNull String parameterName, @NotNull String type) {
        super("unsupported parameter type " + type + " (parameter '" + parameterName + "')");
        this.parameterName = parameterName;
        this.type = type;
    }

    public @NotNull String parameterName() {
        return parameterName;
    }

    public @NotNull String type() {
        return type;
    }
}
