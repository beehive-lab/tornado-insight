/*
 * Copyright (c) 2023, APT Group, Department of Computer Science,
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

package uk.ac.manchester.beehive.tornado.plugins.ui.settings;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ui.configuration.JdkComboBox;
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable;
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.ui.components.JBLabel;
import uk.ac.manchester.beehive.tornado.plugins.entity.EnvironmentVariable;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.*;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TornadoSettingsComponent {

    private final JPanel myMainPanel;

    private final TextFieldWithBrowseButton myTornadoEnv = new TextFieldWithBrowseButton();

    private ProjectSdksModel jdkModel;

    private JdkComboBox myJdk;

    private final JBTextField myMaxArraySize = new JBTextField(4);

    public TornadoSettingsComponent() {
        jdkModel = ProjectStructureConfigurable.getInstance(ProjectManager.getInstance().getDefaultProject()).getProjectJdksModel();
        myJdk = new JdkComboBox(null,
                jdkModel,
                sdkTypeId -> JavaSdk.getInstance() == sdkTypeId,
                null, null, null);

        myTornadoEnv.addBrowseFolderListener("TornadoVM Root Folder", "Choose the .sh file",
                null,
                new FileChooserDescriptor(false, true, false, false, false, false) {
                });

        String INNER_COMMENT = MessageBundle.message("ui.settings.comment.env");

        JPanel innerGrid = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("TornadoVM Root:"), myTornadoEnv)
                .addLabeledComponent(new JBLabel("Java SDK:"), myJdk)
                .addLabeledComponent(new JBLabel(" "), new JLabel("<html><div style='width:400px; color:gray; font-size:15px;'>" + INNER_COMMENT + "</div></html>"))
                .addVerticalGap(10)
                .getPanel();

        JPanel maxArraySizePanel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Max array size:"), myMaxArraySize, 1)
                .addLabeledComponent(new JBLabel(" "), new JLabel("<html><div style='width:400px; color:gray; font-size:15px;'>" + MessageBundle.message("ui.settings.comment.size") + "</div></html>"))
                .getPanel();

        maxArraySizePanel.setBorder(IdeBorderFactory.createTitledBorder(MessageBundle.message("ui.settings.group.dynamic")));
        myMainPanel = FormBuilder.createFormBuilder()
                .addComponent(innerGrid)
                .addComponent(maxArraySizePanel)
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

    public Sdk getJdk(){
        return myJdk.getSelectedJdk();
    }

    public void setMyJdk(Sdk sdk){
        myJdk.setSelectedJdk(sdk);
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
        String parameterSize = myMaxArraySize.getText();
        AtomicReference<String> stringAtomicReference = new AtomicReference<>();
        stringAtomicReference.set("");
        if (StringUtil.isEmpty(path))
            return MessageBundle.message("ui.settings.validation.emptyTornadovm");
        if (myJdk.getSelectedJdk() == null) {
            return MessageBundle.message("ui.settings.validation.emptyJava");
        }
        String versionString = myJdk.getSelectedJdk().getVersionString();
        String regEx = "(?:version\\s+)?(\\d+\\.\\d+\\.\\d+)";
        Pattern compile = Pattern.compile(regEx);
        Matcher matcher = compile.matcher(versionString);
        if (matcher.find()){
            String version = matcher.group(1);
            String[] split = version.split("\\.");
            int majorVersion = Integer.parseInt(split[0]);
            if (majorVersion < 21){
                return MessageBundle.message("ui.settings.validation.javaVersion");
            }
        }

        if (StringUtil.isEmpty(parameterSize)){
            return MessageBundle.message("ui.settings.validation.emptySize");
        }
        try {
            int size = Integer.parseInt(parameterSize);
            if (size >= 16384 || size <= 0) return MessageBundle.message("ui.settings.validation.invalidSize");
        } catch (NumberFormatException e) {
            return MessageBundle.message("ui.settings.validation.invalidSize");
        }
        try {
            EnvironmentVariable.parseFile(path);
        } catch (IOException e) {
            stringAtomicReference.set(MessageBundle.message("ui.settings.validation.invalidTornadovm"));
        }
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            GeneralCommandLine commandLine = new GeneralCommandLine();
            commandLine.setExePath("/bin/sh");
            commandLine.addParameter("-c");
            commandLine.addParameter("export JAVA_HOME=" + EnvironmentVariable.getJavaHome()
                    + ";export PATH=" + EnvironmentVariable.getPath()
                    + ";export CMAKE_ROOT=" + EnvironmentVariable.getCmakeRoot()
                    + ";export TORNADO_SDK=" + EnvironmentVariable.getTornadoSdk()
                    + ";tornado --device");
            try {
                CapturingProcessHandler handler = new CapturingProcessHandler(commandLine);
                System.out.println(commandLine.getCommandLineString());
                ProcessOutput output = handler.runProcess();
                System.out.println(output);
                if (output.getExitCode() != 0) {
                    stringAtomicReference.set(MessageBundle.message("ui.settings.validation.invalidTornadovm"));
                }
            } catch (Exception e) {
                stringAtomicReference.set(MessageBundle.message("ui.settings.validation.invalidTornadovm"));
            }
        }, MessageBundle.message("ui.settings.validation.progress"), true, null);
        return stringAtomicReference.get();
    }
}

