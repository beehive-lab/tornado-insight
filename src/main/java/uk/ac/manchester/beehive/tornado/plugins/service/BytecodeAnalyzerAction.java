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
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.jcef.JBCefBrowser;
import uk.ac.manchester.beehive.tornado.plugins.ui.settings.TornadoSettingState;
import uk.ac.manchester.beehive.tornado.plugins.util.MessageBundle;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BytecodeAnalyzerAction extends AnAction {

    private static final List<String> REQUIRED_MODULES = List.of(
            "streamlit", "networkx", "matplotlib", "plotly", "graphviz"
    );

    private static volatile Process streamlitProcess;
    private static volatile String lastUrl;
    private static volatile String lastPythonSpec;

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
            // 0) Resolve a Python interpreter automatically
            String pythonExe = resolvePython(project);
            if (pythonExe == null) {
                Messages.showErrorDialog(project,
                        "Could not find a Python interpreter.\n" +
                                "Tried project SDK (if any) and common defaults (python3/python/py -3).\n" +
                                "Try launching IDE from a terminal or installing Python.",
                        "Python Not Found");
                return;
            }
            lastPythonSpec = pythonExe;

            // 1) Check missing Python modules on the resolved interpreter
            List<String> missing = findMissingModules(pythonExe, REQUIRED_MODULES, Duration.ofSeconds(15));

            if (!missing.isEmpty()) {
                // Ask the user once if they want to install
                int choice = Messages.showYesNoDialog(
                        project,
                        "TornadoViz requires the following Python packages:\n\n" +
                                String.join(", ", REQUIRED_MODULES) + "\n\n" +
                                "Missing: " + String.join(", ", missing) + "\n\n" +
                                "Do you want me to install the missing packages automatically?\n" +
                                "I will do into:\n" + pythonExe + "\n\n" +
                                "(I will run: \n" + pythonExe + " -m pip install <packages>)",
                        "Install Required Python Packages?",
                        "Install", "Cancel", null
                );
                if (choice != Messages.YES) return;

                boolean ok = installPackagesWithProgress(project, pythonExe, missing);
                if (!ok) {
                    Messages.showErrorDialog(project,
                            "Failed to install required packages.\n" +
                                    "Interpreter: " + pythonExe + "\n" +
                                    "You can install manually:\n" +
                                    pythonExe + " -m pip install " + String.join(" ", missing),
                            "Install Failed");
                    return;
                }

                // Re-check after install
                missing = findMissingModules(pythonExe, REQUIRED_MODULES, Duration.ofSeconds(15));
                if (!missing.isEmpty()) {
                    Messages.showErrorDialog(project,
                            "Some packages are still missing after installation:\n" +
                                    String.join(", ", missing) + "\n\n" +
                                    "Interpreter: " + pythonExe + "\n" +
                                    "If you are on macOS and use Homebrew/conda/venv, ensure this is the same Python\n" +
                                    "the IDE/plugin is launching. You may need to install Graphviz system binary (`dot`) separately.",
                            "Packages Still Missing");
                    return;
                }
            }

            // 2) Kill previous server if running
            stopStreamlitIfRunning();

            // 3) Stage resources
            Path workDir = Files.createTempDirectory("tornado-bytecode-vis");
            Path scriptPath = copyResource("/lib/tornadovm-bytecode-analyzer/tornado-visualizer-fixed.py",
                    workDir.resolve("tornado-visualizer-fixed.py"));
            copyResourceIfExists("/lib/tornadovm-bytecode-analyzer/docs/images/basic_view.png",
                    workDir.resolve("docs").resolve("images").resolve("basic_view.png"));

            // 4) Start Streamlit and parse "Local URL: …"
            StartResult sr = startStreamlit(pythonExe, scriptPath, workDir);
            lastUrl = sr.url();

            // 5) Launch embedded browser
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
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e2) {
                    stopStreamlitIfRunning();
                }
            });

        } catch (Exception ex) {
            Messages.showErrorDialog(project,
                    "Failed to run analysis:\n" + ex.getMessage() +
                            (lastUrl != null ? ("\nLast URL: " + lastUrl) : "") +
                            (lastPythonSpec != null ? ("\nPython: " + lastPythonSpec) : ""),
                    "Error");
        }
    }

    private static String resolvePython(Project project) {
        // 1) If the Python plugin is present and a project interpreter is configured, use it.
        String fromPythonPlugin = getProjectPythonViaPythonPlugin(project);
        if (fromPythonPlugin != null && !fromPythonPlugin.isBlank()) return fromPythonPlugin;

        // 2) Try common candidates (OS-aware)
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> candidates = new ArrayList<>();
        if (isWindows) {
            candidates.add("py -3");
            candidates.add("python");
        } else {
            candidates.add("python");
            candidates.add("/usr/bin/python3");
        }
        for (String spec : candidates) {
            if (canRun(spec)) return spec;
        }
        return null;
    }

    private static String getProjectPythonViaPythonPlugin(Project project) {
        try {
            Class<?> util = Class.forName("com.jetbrains.python.sdk.PythonSdkUtil");
            java.lang.reflect.Method mFind = util.getMethod("findPythonSdk", Project.class);
            Object sdk = mFind.invoke(null, project);
            if (sdk == null) return null;
            java.lang.reflect.Method mHome = sdk.getClass().getMethod("getHomePath");
            Object home = mHome.invoke(sdk);
            return home != null ? home.toString() : null;
        } catch (Throwable ignored) {
            // Python plugin not installed or API not available
            return null;
        }
    }

    private static boolean canRun(String interpreterSpec) {
        try {
            List<String> cmd = splitCommand(interpreterSpec);
            cmd.add("-c"); cmd.add("import sys; print(sys.version)");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            maybePatchPath(pb);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = readAll(p.getInputStream());
            return p.waitFor(7, TimeUnit.SECONDS) && p.exitValue() == 0 && out.trim().length() > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static List<String> findMissingModules(String pythonExe, List<String> modules, Duration timeout) throws Exception {
        String code = String.join("\n",
                "import importlib, sys",
                "missing = []",
                "mods = " + toPyList(modules),
                "for m in mods:",
                "    try: importlib.import_module(m)",
                "    except Exception as e: missing.append(m)",
                "print(','.join(missing))"
        );
        List<String> cmd = splitCommand(pythonExe);
        cmd.add("-c"); cmd.add(code);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        maybePatchPath(pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = readAllWithTimeout(p.getInputStream(), timeout);
        p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);

        String s = out == null ? "" : out.trim();
        if (s.isEmpty()) return Collections.emptyList();
        List<String> missing = new ArrayList<>();
        for (String part : s.split(",")) {
            if (!part.isBlank()) missing.add(part.trim());
        }
        return missing;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static boolean installPackagesWithProgress(Project project, String pythonExe, List<String> packages) {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            if (indicator != null) {
                indicator.setIndeterminate(true);
                indicator.setText("Installing Python packages…");
            }
            try {
                // 1) Ensure pip is present & recent (best effort, ignore failures)
                runPip(pythonExe, List.of("install", "--upgrade", "pip"), Duration.ofMinutes(2));

                // 2) Try normal install first
                int code = runPip(pythonExe, concat(List.of("install"), packages), Duration.ofMinutes(5));
                if (code == 0) return true;

                // 3) If site-packages is not writable, retry with --user
                int codeUser = runPip(pythonExe, concat(List.of("install", "--user"), packages), Duration.ofMinutes(5));
                return codeUser == 0;
            } catch (Exception ex) {
                return false;
            }
        }, "Installing Python Packages", true, project);
    }

    private static int runPip(String pythonExe, List<String> pipArgs, Duration timeout) throws Exception {
        List<String> cmd = splitCommand(pythonExe);
        cmd.add("-m"); cmd.add("pip");
        cmd.addAll(pipArgs);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        maybePatchPath(pb);
        pb.environment().putIfAbsent("MPLBACKEND", "Agg");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Stream output to stdout to aid debugging
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            long deadline = System.nanoTime() + timeout.toNanos();
            while ((line = r.readLine()) != null) {
                System.out.println("[pip] " + line);
                if (System.nanoTime() > deadline) break;
            }
        }
        boolean finished = p.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        Integer code;
        if (finished) {
            code = p.exitValue();
        } else {
            p.destroy();
            System.out.println("[pip] Process timed out and was destroyed.");
            code = -1;
        }
        return code;
    }

    // --------------------------------------------------------------------------------------------
    // Streamlit start & UI
    // --------------------------------------------------------------------------------------------

    private static void stopStreamlitIfRunning() {
        if (streamlitProcess != null && streamlitProcess.isAlive()) {
            try { streamlitProcess.destroy(); } catch (SecurityException | IllegalStateException ignored) {}
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

    private static StartResult startStreamlit(String interpreterSpec, Path scriptPath, Path workDir) throws Exception {
        int port = chooseFreePort();

        List<String> cmd = splitCommand(interpreterSpec);
        cmd.add("-m"); cmd.add("streamlit");
        cmd.add("run");
        cmd.add("--server.headless=true");
        cmd.add("--server.port=" + port);
        cmd.add("--browser.gatherUsageStats=false");
        cmd.add(scriptPath.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        maybePatchPath(pb);
        // Matplotlib backend for Streamlit processes
        pb.environment().putIfAbsent("MPLBACKEND", "Agg");

        Process p = pb.start();

        // Parse "Local URL: …"
        Pattern LOCAL_URL = Pattern.compile("Local URL:\\s*(http[^\\s]+)", Pattern.CASE_INSENSITIVE);
        CountDownLatch ready = new CountDownLatch(1);
        StringBuilder foundUrl = new StringBuilder();

        Thread drainer = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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

    // --------------------------------------------------------------------------------------------
    // Utilities
    // --------------------------------------------------------------------------------------------

    private static List<String> splitCommand(String spec) {
        List<String> cmd = new ArrayList<>();
        for (String part : spec.split("\\s+")) if (!part.isBlank()) cmd.add(part);
        return cmd;
    }

    /** For macOS GUI launches, PATH often misses Homebrew; prepend it for child processes. Safe no-op elsewhere. */
    private static void maybePatchPath(ProcessBuilder pb) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            Map<String, String> env = pb.environment();
            String path = env.getOrDefault("PATH", "");
            // Add common Homebrew locations if missing (Apple Silicon first, then Intel)
            String brewAs = "/opt/homebrew/bin";
            String brewIntel = "/usr/local/bin";
            String patched = path;
            if (!patched.contains(brewAs)) patched = brewAs + ":" + patched;
            if (!patched.contains(brewIntel)) patched = brewIntel + ":" + patched;
            env.put("PATH", patched);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String readAllWithTimeout(InputStream in, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            while (System.nanoTime() < deadline) {
                if (r.ready()) {
                    String line = r.readLine();
                    if (line == null) break;
                    sb.append(line).append('\n');
                } else {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                }
            }
        }
        return sb.toString();
    }

    private static String toPyList(List<String> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(xs.get(i).replace("'", "\\'")).append("'");
        }
        return sb.append("]").toString();
    }

    // Optional: recursively delete the staging dir on window close
    @SuppressWarnings("unused")
    private static void deleteRecursive(Path root) throws IOException {
        if (Files.notExists(root)) return;
        Files.walk(root)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ex) {
                        System.err.println("[plugin] Failed to delete " + p + ": " + ex.getMessage());
                    }
                });
    }
}
