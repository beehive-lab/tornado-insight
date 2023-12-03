package uk.ac.manchester.beehive.tornado.plugins.error;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.ErrorReportSubmitter;
import com.intellij.openapi.diagnostic.IdeaLoggingEvent;
import com.intellij.openapi.diagnostic.SubmittedReportInfo;
import com.intellij.openapi.extensions.PluginId;
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

public class ErrorSubmitter extends ErrorReportSubmitter {

    private String url = "https://github.com/TaisZ/TornadoInsight/issues/new?";
    private final String pluginId = "com.tais.Tornado_Plugins";
    private final String label = "exception";
    private final int stackTraceLen = 6500;
    @Override
    public @NlsActions.ActionText @NotNull String getReportActionText() {
        return "Report to Vendor";
    }

    @Override
    public boolean submit(IdeaLoggingEvent @NotNull [] events,
                          @Nullable String additionalInfo,
                          @NotNull Component parentComponent,
                          @NotNull Consumer<? super SubmittedReportInfo> consumer) {

        IdeaLoggingEvent event = ArrayUtil.getFirstElement(events);
        String title = "Exception: ";
        String stacktrace = "Please paste the full stacktrace from the IDEA error popup.\n";
        if (events != null){
            String throwableText = event.getThrowableText();
            String exceptionTitle = throwableText.lines().findFirst().get();
            title += (!StringUtil.isEmptyOrSpaces(exceptionTitle) ? exceptionTitle : "<Fill in title>");
            if (!StringUtil.isEmptyOrSpaces(throwableText)){
                String quotes = "\n```\n";
                stacktrace += quotes + StringUtil.first(
                        throwableText,
                        stackTraceLen,
                        true) + quotes;
            }
        }

        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(pluginId));
        String pluginVersion = (plugin != null) ? plugin.getVersion() : "";
        String ideaVersion = ApplicationInfo.getInstance().getBuild().asString();
        StringBuilder template = new StringBuilder();
        template.append("### Description\n");
        if (additionalInfo != null) template.append(additionalInfo).append("\n");
        template.append("\n");
        template.append("### Stacktrace\n").append(stacktrace).append("\n");
        template.append("### Version and Enviroment Details\n")
                .append("Operation system: ").append(SystemInfo.getOsNameAndVersion()).append("\n")
                .append("IDE version: ").append(ideaVersion).append("\n")
                .append("Plugin version: ").append(pluginVersion).append("\n");
        Charset utf8 = StandardCharsets.UTF_8;
        url = String.format("%stitle=%s&labels=%s&body=%s", url,
                URLEncoder.encode(title,utf8), URLEncoder.encode(label,utf8),
                URLEncoder.encode(template.toString(),utf8));
        BrowserUtil.browse(url);
        consumer.consume(
               new SubmittedReportInfo(null, "GitHub issue", SubmittedReportInfo.SubmissionStatus.NEW_ISSUE));

        return true;
    }


}
