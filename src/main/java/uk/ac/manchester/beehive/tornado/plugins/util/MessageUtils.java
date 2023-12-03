package uk.ac.manchester.beehive.tornado.plugins.util;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import uk.ac.manchester.beehive.tornado.plugins.ui.console.ConsoleWindowFactory;
import org.apache.commons.lang.time.DateFormatUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Date;

@Service
public final class MessageUtils implements Disposable {
    
    private final Project project;
    private ConsoleView consoleView;
    private ToolWindow toolWindow;

    public MessageUtils(Project project) {
        this.project = project;
        this.toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ConsoleWindowFactory.ID);
    }

    @NotNull
    public static MessageUtils getInstance(Project project) {
        return project.getService(MessageUtils.class);
    }


    public static void showMsg(JComponent component, MessageType messageType, String title, String body) {
        JBPopupFactory factory = JBPopupFactory.getInstance();
        BalloonBuilder builder = factory.createHtmlTextBalloonBuilder(body, messageType, null);
        builder.setTitle(title);
        builder.setFillColor(JBColor.background());
        Balloon b = builder.createBalloon();
        Rectangle r = component.getBounds();
        RelativePoint p = new RelativePoint(component, new Point(r.x + r.width, r.y + 30));
        b.show(p, Balloon.Position.atRight);
    }

    public void showInfoMsg(String title, String body) {
        showConsole(() -> {
            printTitle(title, ConsoleViewContentType.NORMAL_OUTPUT);
            printBody(body, ConsoleViewContentType.NORMAL_OUTPUT);
            consoleView.print("\n", ConsoleViewContentType.NORMAL_OUTPUT);
        });
    }

    public void showWarnMsg(String title, String body) {
        showConsole(() -> {
            printTitle(title, ConsoleViewContentType.LOG_INFO_OUTPUT);
            printBody(body, ConsoleViewContentType.LOG_INFO_OUTPUT);
            consoleView.print("\n", ConsoleViewContentType.LOG_INFO_OUTPUT);
        });
    }

    public void showErrorMsg(String title, String body) {
        showConsole(() -> {
            printTitle(title, ConsoleViewContentType.ERROR_OUTPUT);
            printBody(body, ConsoleViewContentType.ERROR_OUTPUT);
            consoleView.print("\n", ConsoleViewContentType.ERROR_OUTPUT);
        });
    }

    private void printTitle(String title, ConsoleViewContentType contentType) {
        if (title.equals("info") || title.equals("warning") || title.equals("error")) {
            consoleView.print("> " + DateFormatUtils.format(new Date(), "yyyy/MM/dd' 'HH:mm:ss") + "\n", contentType);
        } else {
            consoleView.print("> " + DateFormatUtils.format(new Date(), "yyyy/MM/dd' 'HH:mm:ss") + "\t" + title + "\n", contentType);
        }
    }

    private void printBody(String body, ConsoleViewContentType contentType) {
        String[] bodys = body.split("\n");
        for (String s : bodys) {
                consoleView.print(s + "\n", contentType);
        }
    }

    private void showConsole(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (toolWindow == null) {
                toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ConsoleWindowFactory.ID);
            }
            if (toolWindow == null) {
                return;
            }
            if (!toolWindow.isAvailable()) {
                toolWindow.setAvailable(true);
            }
            if (!toolWindow.isActive()) {
                toolWindow.activate(null);
            }
            if (consoleView == null) {
                this.consoleView = ConsoleWindowFactory.getDataContext(project).getData(DataKeys.TORNADO_CONSOLE_VIEW);
            }
            consoleView.requestScrollingToEnd();
            runnable.run();
        });


    }

    @Override
    public void dispose() {
        if (consoleView != null) {
            Disposer.dispose(consoleView);
        }
    }
}
