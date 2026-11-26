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

package uk.ac.manchester.beehive.tornado.plugins.convert;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * What {@link CallSiteRewriter} did to the calls to a converted method in its file.
 *
 * @param rewritten the calls that now launch the kernel with TornadoVM
 * @param methods   the methods they are in
 * @param graphs    the TaskGraphs built for them; fewer than the calls when calls replay a plan
 * @param skipped   why each call that was left as it is could not be rewritten, one entry per call
 */
record CallSites(int rewritten, int methods, int graphs, @NotNull List<String> skipped) {

    CallSites {
        skipped = List.copyOf(skipped);
    }
}
