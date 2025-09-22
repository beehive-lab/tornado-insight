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

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BytecodeAnalyzerAction extends AnAction {

    private static volatile Process streamlitProcess;   // keep handle to kill on relaunch/close
    private static volatile String lastUrl;             // debug: last discovered URL

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
            // 1) Sanity: streamlit available?
            if (!isStreamlitAvailable()) {
                Messages.showErrorDialog(project,
                        MessageBundle.message("ui.settings.group.visualizer.not.found"),
                        "Streamlit Not Found");
                return;
            }

            // 2) Kill previous server if running
            stopStreamlitIfRunning();

            // 3) Stage resources into a temp working dir that mirrors expected layout
            Path workDir = Files.createTempDirectory("tornado-bytecode-vis");
            Path scriptPath = copyResource("/lib/tornadovm-bytecode-analyzer/tornado-visualizer-fixed.py",
                    workDir.resolve("tornado-visualizer-fixed.py"));
            // Optional image (don’t fail if absent)
            copyResourceIfExists("/lib/tornadovm-bytecode-analyzer/docs/images/basic_view.png",
                    workDir.resolve("docs").resolve("images").resolve("basic_view.png"));

            // 4) Start streamlit once, parse “Local URL: …”
            StartResult sr = startStreamlit(scriptPath, workDir);
            lastUrl = sr.url();

            // 5) Launch JCEF at the actual URL (with cache-buster)
            String cacheBusted = lastUrl + (lastUrl.contains("?") ? "&" : "?") + "cb=" + System.currentTimeMillis();
            JBCefBrowser browser = new JBCefBrowser(cacheBusted);
            SwingUtilities.invokeLater(() -> browser.getCefBrowser().reloadIgnoreCache());

            JComponent component = browser.getComponent();
            JFrame frame = new JFrame(MessageBundle.message("ui.settings.group.visualizer"));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(component);
            frame.setSize(1200, 800);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // 6) Ensure cleanup on window close
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) {
                    stopStreamlitIfRunning();
                }
            });

        } catch (Exception ex) {
            Messages.showErrorDialog(project,
                    "Failed to run analysis:\n" + ex.getMessage() +
                            (lastUrl != null ? ("\nLast URL: " + lastUrl) : ""),
                    "Error");
        }
    }

    private static boolean isStreamlitAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("python3", "-m", "streamlit", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void stopStreamlitIfRunning() {
        if (streamlitProcess != null && streamlitProcess.isAlive()) {
            try { streamlitProcess.destroy(); } catch (Throwable ignored) {}
        }
        streamlitProcess = null;
    }

    private static Path copyResource(String resourcePath, Path out) throws IOException {
        try (InputStream in = BytecodeAnalyzerAction.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new FileNotFoundException("Resource not found: " + resourcePath);
            }
            Files.createDirectories(out.getParent());
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        }
    }

    private static void copyResourceIfExists(String resourcePath, Path out) {
        try (InputStream in = BytecodeAnalyzerAction.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.out.println("[plugin] optional resource not found: " + resourcePath);
                return;
            }
            Files.createDirectories(out.getParent());
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ioe) {
            System.out.println("[plugin] failed copying optional resource: " + resourcePath + " -> " + ioe.getMessage());
        }
    }

    private static int chooseFreePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private record StartResult(Process process, String url) {}

    private static StartResult startStreamlit(Path scriptPath, Path workDir) throws Exception {
        int port = chooseFreePort();

        ProcessBuilder pb = new ProcessBuilder(
                "python3", "-m", "streamlit",
                "run",
                "--server.headless=true",
                "--server.port=" + port,
                "--browser.gatherUsageStats=false",
                scriptPath.toString()
        );
        pb.directory(workDir.toFile());     // critical: make relative assets (docs/images/...) work
        pb.redirectErrorStream(true);

        Process p = pb.start();

        // Drain and parse URL
        Pattern LOCAL_URL = Pattern.compile("Local URL:\\s*(http[^\\s]+)", Pattern.CASE_INSENSITIVE);
        CountDownLatch ready = new CountDownLatch(1);
        StringBuilder foundUrl = new StringBuilder();

        Thread drainer = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[streamlit] " + line);
                    Matcher m = LOCAL_URL.matcher(line);
                    if (m.find()) {
                        foundUrl.setLength(0);
                        foundUrl.append(m.group(1).trim());
                        ready.countDown();
                    }
                    if (line.contains("is already in use")) ready.countDown();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                ready.countDown();
            }
        }, "streamlit-drain");
        drainer.setDaemon(true);
        drainer.start();

        if (!ready.await(25, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Streamlit did not report a Local URL in time.");
        }

        String url = (foundUrl.length() > 0) ? foundUrl.toString() : ("http://localhost:" + port);
        streamlitProcess = p;
        return new StartResult(p, url);
    }

    // Optional: recursively delete the staging dir on window close
    @SuppressWarnings("unused")
    private static void deleteRecursive(Path root) throws IOException {
        if (Files.notExists(root)) return;
        Files.walk(root)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
    }
}
