/*
 * Copyright (c) 2025, APT Group, Department of Computer Science,
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
package uk.ac.manchester.beehive.tornado.plugins.service;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.jcef.JBCefBrowser;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.JComponent;
import javax.swing.JFrame;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class BytecodeAnalyzerAction extends AnAction {

    private static Process streamlitProcess;

    /**
     * Check if Streamlit is installed and available.
     *
     * @return true if Streamlit is available, false otherwise
     */
    private boolean isStreamlitAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-m", "streamlit", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();

        if (!TornadoSettingState.getInstance().bytecodeVisualizerEnabled) {
            Messages.showInfoMessage(project,
                    MessageBundle.message("ui.settings.group.visualizer.disabled"),
                    "Warning");
            return;
        }

        try {
            if (!isStreamlitAvailable()) {
                Messages.showErrorDialog(project,MessageBundle.message("ui.settings.group.visualizer.not.found"),
                        "Streamlit Not Found");
                return;
            }

            if (streamlitProcess == null || !streamlitProcess.isAlive()) {
                InputStream scriptStream = getClass().getResourceAsStream(
                        "/lib/tornadovm-bytecode-analyzer/tornado-visualizer-fixed.py");
                if (scriptStream == null) {
                    throw new FileNotFoundException("The TornadoVM Visualizer Python script not found in plugin resources.");
                }

                Path tempScript = Files.createTempFile("tornado-bytecode-visualizer", ".py");
                Files.copy(scriptStream, tempScript, StandardCopyOption.REPLACE_EXISTING);
                String scriptPath = tempScript.toAbsolutePath().toString();

                ProcessBuilder pb = new ProcessBuilder(
                        "python3", "-m", "streamlit", "run", scriptPath,
                        "--server.headless", "true"
                );

                pb.redirectErrorStream(true);
                streamlitProcess = pb.start();

                // Wait a few seconds to let Streamlit start (better: check port availability)
                Thread.sleep(3000);
            }

            // Launch the embedded browser
            JBCefBrowser jbCefBrowser = new JBCefBrowser("http://localhost:8501");
            JComponent component = jbCefBrowser.getComponent();

            JFrame frame = new JFrame(MessageBundle.message("ui.settings.group.visualizer"));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(component);
            frame.setSize(1200, 800);
            frame.setVisible(true);

        } catch (Exception ex) {
            Messages.showErrorDialog(project,
                    "Failed to run analysis:\n" + ex.getMessage(),
                    "Error");
        }
    }
}