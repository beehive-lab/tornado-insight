package com.tais.tornado_plugins.ui.settings;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UI;
import com.tais.tornado_plugins.util.MessageBundle;

import javax.swing.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class TornadoSettingsComponent {

    private final JPanel myMainPanel;

    private final TextFieldWithBrowseButton myTornadoEnv = new TextFieldWithBrowseButton();

    private final TextFieldWithBrowseButton myJava21Path = new TextFieldWithBrowseButton();

    private final JBTextField myMaxArraySize = new JBTextField(4);


    public TornadoSettingsComponent() {
        myTornadoEnv.addBrowseFolderListener("TornadoVM Root Folder", "Choose the .sh file",
                null,
                new FileChooserDescriptor(false, true, false, false, false, false) {
                });

        myJava21Path.addBrowseFolderListener("Java21 Home", "Choose the Java_Home for Java 21",
                null,
                new FileChooserDescriptor(false, true, false, false, false, false) {
                });


        String INNER_COMMENT = MessageBundle.message("ui.settings.comment.env");

        JPanel innerGrid = UI.PanelFactory.grid().splitColumns()
                .add(UI.PanelFactory.panel(myTornadoEnv).withLabel(MessageBundle.message("ui.settings.label.tornado")))
                .add(UI.PanelFactory.panel(myJava21Path).withLabel(MessageBundle.message("ui.settings.label.java")))
                .createPanel();

        JPanel panel = UI.PanelFactory.panel(innerGrid).withComment(INNER_COMMENT).createPanel();
        panel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.runtime")));
        JPanel Java21 = UI.PanelFactory.panel(myMaxArraySize)
                .withLabel(MessageBundle.message("ui.setting.label.size"))
                .withComment(MessageBundle.message("ui.settings.comment.size")).createPanel();
        Java21.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.dynamic")));


        myMainPanel = FormBuilder.createFormBuilder()
                .addComponent(panel)
                .addComponent(Java21)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public JPanel getPanel() {
        return myMainPanel;
    }

    public String getTornadoEnvPath() {
        return myTornadoEnv.getText();
    }

    public void setTornadoEnvPath(String path) {
        myTornadoEnv.setText(path);
    }

    public String getJava21Path() {
        return myJava21Path.getText();
    }

    public void setJava21Path(String path) {
        myJava21Path.setText(path);
    }

    public int getMaxArraySize() {
        if (myMaxArraySize.getText().isEmpty() || Objects.equals(myMaxArraySize.getText(), "0")) {
            return 32;
        }
        return Integer.parseInt(myMaxArraySize.getText());
    }

    public void setMaxArraySize(int size) {
        myMaxArraySize.setText(String.valueOf(size));
    }

    public String isValidPath() {
        String path = myTornadoEnv.getText() + "/setvars.sh";
        String JavaPath = myJava21Path.getText();
        String parameterSize = myMaxArraySize.getText();
        AtomicReference<String> stringAtomicReference = new AtomicReference<>();
        stringAtomicReference.set("");
        if (StringUtil.isEmpty(path))
            return MessageBundle.message("ui.settings.validation.emptyTornadovm");
        if (StringUtil.isEmpty(JavaPath))
            return MessageBundle.message("ui.settings.validation.emptyJava");
        if (StringUtil.isEmpty(parameterSize)){
            return MessageBundle.message("ui.settings.validation.emptySize");
        }
        try {
            int size = Integer.parseInt(parameterSize);
            if (size >= 16384 || size <= 0) return MessageBundle.message("ui.settings.validation.invalidSize");
        } catch (NumberFormatException e) {
            return MessageBundle.message("ui.settings.validation.invalidSize");
        }
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            GeneralCommandLine commandLine = new GeneralCommandLine();
            commandLine.setExePath("/bin/sh");
            commandLine.addParameter("-c");
            commandLine.addParameter("source " + path + ";tornado --device");
            try {
                CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
                ProcessOutput output = handler.runProcess();
                if (output.getExitCode() != 0) {
                    stringAtomicReference.set(MessageBundle.message("ui.settings.validation.invalidTornadovm"));
                }
            } catch (Exception e) {
                stringAtomicReference.set(MessageBundle.message("ui.settings.validation.invalidTornadovm"));
            }
            //Validate is Java 21. The validation process needs update;
            commandLine = new GeneralCommandLine();
            commandLine.setExePath(JavaPath + "/bin/java");
            commandLine.addParameter("-version");
            try {
                ProcessOutput processOutput = ExecUtil.execAndGetOutput(commandLine);
                if (!processOutput.toString().contains("java version \"21\"")
                        && !processOutput.toString().contains("GraalVM 21")) {
                    stringAtomicReference.set(MessageBundle.message("ui.settings.validation.javaVersion"));
                }
                TornadoSettingState.getInstance().isValid = true;
            } catch (Exception e) {
                stringAtomicReference.set(MessageBundle.message("ui.settings.validation.invalidJava"));
            }
        }, MessageBundle.message("ui.settings.validation.progress"), true, null);
        return stringAtomicReference.get();
    }
}

