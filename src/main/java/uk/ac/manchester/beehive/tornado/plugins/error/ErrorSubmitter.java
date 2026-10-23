/*
 * Copyright (c) 2023, 2026, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.error;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.ErrorReportSubmitter;
import com.intellij.openapi.diagnostic.IdeaLoggingEvent;
import com.intellij.openapi.diagnostic.SubmittedReportInfo;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.util.NlsActions;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

public class ErrorSubmitter extends ErrorReportSubmitter {

    private static final String NEW_ISSUE_URL = "https://github.com/beehive-lab/tornado-insight/issues/new?";
    private final String label = "exception";
    private final int stackTraceLen = 6500;
    @Override
    public @NlsActions.ActionText @NotNull String getReportActionText() {
        return MessageBundle.message("error.report.action");
    }

    @Override
    public boolean submit(IdeaLoggingEvent @NotNull [] events,
                          @Nullable String additionalInfo,
                          @NotNull Component parentComponent,
                          @NotNull Consumer<? super SubmittedReportInfo> consumer) {

        IdeaLoggingEvent event = ArrayUtil.getFirstElement(events);
        String title = "Exception: ";
        String stacktrace = "Please paste the full stacktrace from the IDEA error popup.\n";
        if (event != null) {
            String throwableText = StringUtil.notNullize(event.getThrowableText());
            String exceptionTitle = throwableText.lines().findFirst().orElse("");
            title += (!StringUtil.isEmptyOrSpaces(exceptionTitle) ? exceptionTitle : "<Fill in title>");
            if (!StringUtil.isEmptyOrSpaces(throwableText)){
                String quotes = "\n```\n";
                stacktrace += quotes + StringUtil.first(
                        throwableText,
                        stackTraceLen,
                        true) + quotes;
            }
        }

        PluginDescriptor plugin = getPluginDescriptor();
        String pluginVersion = (plugin != null) ? plugin.getVersion() : "";
        String ideaVersion = ApplicationInfo.getInstance().getBuild().asString();
        StringBuilder template = new StringBuilder();
        template.append("### Description\n");
        if (additionalInfo != null) template.append(additionalInfo).append("\n");
        template.append("\n");
        template.append("### Stacktrace\n").append(stacktrace).append("\n");
        template.append("### Version and Environment Details\n")
                .append("Operation system: ").append(SystemInfo.OS_NAME + " " + SystemInfo.OS_VERSION).append("\n")
                .append("IDE version: ").append(ideaVersion).append("\n")
                .append("Plugin version: ").append(pluginVersion).append("\n");
        Charset utf8 = StandardCharsets.UTF_8;
        // Build the URL per report; the base URL must not accumulate query strings across reports
        String url = String.format("%stitle=%s&labels=%s&body=%s", NEW_ISSUE_URL,
                URLEncoder.encode(title,utf8), URLEncoder.encode(label,utf8),
                URLEncoder.encode(template.toString(),utf8));
        BrowserUtil.browse(url);
        consumer.consume(
               new SubmittedReportInfo(null, "GitHub issue", SubmittedReportInfo.SubmissionStatus.NEW_ISSUE));

        return true;
    }


}
