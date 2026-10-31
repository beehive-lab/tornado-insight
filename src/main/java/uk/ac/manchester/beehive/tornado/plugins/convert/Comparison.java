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

import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.ac.manchester.beehive.tornado.plugins.dynamic.KernelVerifier;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The kernels of one method converted to several targets and timed on the device, and the one
 * recommended: the first target (the most readable) unless another verified kernel is faster by
 * more than the threshold.
 *
 * @param entries   one per target, in {@link Target} order
 * @param chosen    the index of the recommended entry
 * @param length    the elements per array of the timed runs
 * @param runs      the timed runs per kernel
 * @param threshold how much faster, in percent, a less readable kernel must be to be recommended
 */
record Comparison(@NotNull List<Entry> entries, int chosen, int length, int runs, int threshold) {

    /**
     * @param result the conversion
     * @param timed  the timed run of its kernel, or {@code null} if it was not verified
     * @param error  why the conversion failed, if {@code result} is {@code null}
     */
    record Entry(@NotNull Target target, @Nullable Converter.Result result, @Nullable KernelVerifier.Outcome timed,
                 @Nullable String error) {

        /** The times, if the kernel was verified and timed. */
        @Nullable KernelVerifier.Timing timing() {
            return timed != null && timed.status() == KernelVerifier.Status.EQUAL ? timed.timing() : null;
        }
    }

    Comparison {
        entries = List.copyOf(entries);
    }

    /** Compares {@code entries} and recommends one. */
    static @NotNull Comparison of(@NotNull List<Entry> entries, int length, int runs, int threshold) {
        return new Comparison(entries, choose(entries, threshold), length, runs, threshold);
    }

    /**
     * Why each failed entry failed, as {@code "Kernel API: error"}; targets that failed for the
     * same reason, such as a server that is down, share one line.
     */
    @NotNull List<String> failures() {
        Map<String, List<String>> targets = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.result() == null) {
                targets.computeIfAbsent(String.valueOf(entry.error()), error -> new ArrayList<>())
                        .add(entry.target().displayName());
            }
        }
        List<String> failures = new ArrayList<>();
        targets.forEach((error, names) -> failures.add(String.join(", ", names) + ": " + error));
        return failures;
    }

    /**
     * The recommended entry: the first timed one, unless a later one is faster by more than
     * {@code threshold} percent (then the fastest of those); without times, the first conversion
     * without problems.
     */
    static int choose(@NotNull List<Entry> entries, int threshold) {
        int base = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).timing() != null) {
                base = i;
                break;
            }
        }
        if (base < 0) {
            for (int i = 0; i < entries.size(); i++) {
                Converter.Result result = entries.get(i).result();
                if (result != null && result.problems().isEmpty()) {
                    return i;
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).result() != null) {
                    return i;
                }
            }
            return 0;
        }
        double limit = entries.get(base).timing().kernel() / (1 + threshold / 100.0);
        int chosen = base;
        for (int i = base + 1; i < entries.size(); i++) {
            KernelVerifier.Timing timing = entries.get(i).timing();
            if (timing != null && timing.kernel() < limit && timing.kernel() < entries.get(chosen).timing().kernel()) {
                chosen = i;
            }
        }
        return chosen;
    }

    /** The comparison as an HTML table, for the preview. */
    @NotNull String table() {
        StringBuilder html = new StringBuilder("<table cellpadding='2'><tr><th align='left'></th><th align='right'>")
                .append(MessageBundle.message("convert.compare.time")).append("</th><th align='right'>")
                .append(MessageBundle.message("convert.compare.speedup")).append("</th><th align='right'>")
                .append(MessageBundle.message("convert.compare.device")).append("</th><th align='right'>")
                .append(MessageBundle.message("convert.compare.transfers")).append("</th><th align='left'></th></tr>");
        Long java = null;
        for (Entry entry : entries) {
            if (entry.timing() != null && entry.timing().java() >= 0) {
                java = entry.timing().java();
                break;
            }
        }
        if (java != null) {
            html.append("<tr><td>").append(MessageBundle.message("convert.compare.java")).append("</td><td align='right'>")
                    .append(duration(java)).append("</td><td align='right'>1×</td><td></td><td></td><td></td></tr>");
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            KernelVerifier.Timing timing = entry.timing();
            html.append("<tr><td>").append(i == chosen ? "<b>" : "").append(entry.target().displayName())
                    .append(i == chosen ? "</b>" : "").append("</td>");
            if (timing != null) {
                html.append("<td align='right'>").append(duration(timing.kernel())).append("</td><td align='right'>")
                        .append(java == null ? "" : speedup(java, timing.kernel())).append("</td><td align='right'>")
                        .append(duration(timing.deviceKernel())).append("</td><td align='right'>")
                        .append(duration(timing.transfers())).append("</td><td>")
                        .append(i == chosen ? MessageBundle.message("convert.compare.recommended") : "").append("</td>");
            } else {
                html.append("<td colspan='4'></td><td>").append(StringUtil.escapeXmlEntities(whyNotTimed(entry))).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>").append(MessageBundle.message("convert.compare.how", length, runs,
                entries.get(0).target().displayName(), threshold));
        return html.toString();
    }

    private static String whyNotTimed(Entry entry) {
        if (entry.result() == null) {
            return MessageBundle.message("convert.compare.failed", String.valueOf(entry.error()));
        }
        if (!entry.result().verified()) {
            return MessageBundle.message("convert.compare.notVerified");
        }
        KernelVerifier.Outcome timed = entry.timed();
        return timed == null || timed.details().isEmpty() ? MessageBundle.message("convert.compare.notTimed")
                : MessageBundle.message("convert.compare.timingFailed", timed.details().get(0));
    }

    static @NotNull String duration(long nanos) {
        double millis = nanos / 1e6;
        return millis >= 100 ? String.format(Locale.ROOT, "%.0f ms", millis)
                : millis >= 1 ? String.format(Locale.ROOT, "%.1f ms", millis)
                : String.format(Locale.ROOT, "%.3f ms", millis);
    }

    // How many times faster (or slower) than Java
    static @NotNull String speedup(long java, long kernel) {
        if (kernel <= 0) {
            return "";
        }
        double ratio = (double) java / kernel;
        return ratio >= 1 ? MessageBundle.message("convert.compare.faster", String.format(Locale.ROOT, "%.1f", ratio))
                : MessageBundle.message("convert.compare.slower", String.format(Locale.ROOT, "%.1f", 1 / ratio));
    }
}
