# Developing TornadoInsight

How to build, run and test the plugin, how the code is organised, and the tools that help when
working on it. For using the plugin, see the [README](../README.md).

## Building and running

You need a JDK 21 to run Gradle; the build downloads the IntelliJ Platform it compiles against.

```bash
git clone https://github.com/beehive-lab/tornado-insight.git
cd tornado-insight
./gradlew buildPlugin    # the plugin zip, in build/distributions/
./gradlew runIde         # a sandbox IDE with the plugin installed
```

The sandbox IDE keeps its own settings and projects under `build/idea-sandbox/`. It inherits the
environment of the shell that starts it, so `TORNADOVM_HOME` must be set there for dynamic
inspection. Do not run Gradle tasks that rebuild the plugin while the sandbox IDE is open: the IDE
cannot reload the plugin and is left with a half-loaded one until it restarts.

To try a build in your everyday IDE, install the zip with *Settings | Plugins | ⚙ | Install Plugin
from Disk...* and restart.

## Code layout

The sources are in `src/main/java/uk/ac/manchester/beehive/tornado/plugins/`, organised after the
four modules of the MPLR '25 paper. Each package has a `package-info.java` that describes it in
more detail.

| Package | Paper | Contents |
|---|---|---|
| `kernel` | §3.1 Static Inspection | What a kernel is and what it reaches (call graph, supported types); depends on no other plugin package |
| `inspection` | §3.1 Static Inspection | One IntelliJ inspection per TornadoVM restriction, and `KernelValidator` |
| `dynamic`, `dynamic.harness`, `dynamic.run` | §3.2 Dynamic Inspection | The pipeline: generate a test program, compile it, pack it, run it with `tornado`, analyse the output |
| `ui.toolwindow`, `ui.console`, `settings` | §3.3 User Interface | The TornadoVM tool window, the TornadoInsight Console, the settings page |
| `sync` | §3.4 Communication and Data Synchronization | The listeners and `TornadoTaskService`, which keeps the valid tasks of the current file |
| `sdk`, `actions`, `templates`, `error`, `util` | | The TornadoVM SDK, toolbar actions, live templates, error reporting, shared helpers |

Resources live in `src/main/resources/`: `META-INF/plugin.xml`, the message bundle
(`messages/TornadoInsightBundle.properties`), the live templates (`liveTemplates/TornadoVM.xml`)
and the supported kernel types (`tornado/supported-kernel-types.txt`).

## Tests

```bash
./gradlew test                                   # all tests
./gradlew test --tests '*TornadoVMTemplatesTest' # one class
```

The tests are in `src/test/java`, mirroring the packages. Most use the IntelliJ test framework with a
stubbed TornadoVM API (`TornadoTestCase`), so they need no TornadoVM SDK. Two tests guard the
structure of the code:

- `PackageDependencyTest` fails on package cycles, on `kernel` using another plugin package, on
  `inspection` using anything but `kernel` and `util`, on packages outside `ui.*` and `actions`
  using `ui.*`, and on wildcard imports of plugin packages.
- `PluginXmlClassNamesTest` loads every class `plugin.xml` names, which catches names left stale
  by a package move.

CI (`.github/workflows/build.yml`) runs `./gradlew test buildPlugin` and `./gradlew verifyPlugin`
on every push and pull request.

## Debug dump

Dynamic inspection generates a test program (a *harness*) around each selected kernel, compiles
it, runs it with `tornado --printKernel`, and deletes it with its temporary folder. The debug
dump keeps these programs, together with the kernel TornadoVM generated for each one, so you can
read them, run them yourself with `tornado`, or attach them to a bug report.

### Turning it on

1. Run the IDE in IntelliJ's internal mode. `./gradlew runIde` does this already. In any other
   IDE, add `idea.is.internal=true` with *Help | Edit Custom Properties* and restart.
2. In the TornadoVM tool window, click the bug icon (**Debug Dump (Internal)**) next to the Run
   button, so it stays pressed. It stays on across restarts until you click it again.
3. Run dynamic inspection as usual.

The icon is hidden outside internal mode, and the dump is off there even if the icon was left
pressed.

### What it keeps

Each run empties `tornadoinsight/` in the system temporary folder (`/tmp` on Linux, `%TEMP%` on
Windows, `/var/folders/...` on macOS), then writes two files per task:

| File | Contents |
|---|---|
| `vectorAddX7k2q.java` | The test program: the kernel method's name plus a random suffix |
| `vectorAddX7k2q.kernel.cl` | The kernel TornadoVM generated: `.cl`, `.cu` or `.ptx`, by backend |

- The test program is formatted with the project's Java code style before it is compiled, so the
  kept file is the program that ran and javac's line numbers match it.
- The kernel is written from the run's output even when the run fails.
- The TornadoInsight Console links to the folder (opens the file manager) and to each file
  (opens the editor).

The code is in `actions.DebugDumpAction` (the toggle), `dynamic.DebugDump` (the folder and
files) and `dynamic.HarnessFormatter` (the formatting).

## Releasing

Releases are made by hand:

1. A `[release] Prepare <x.y.z>` commit sets `version` in `build.gradle` to `<x.y.z>` and adds a
   `Version <x.y.z>` entry to `<change-notes>` in `plugin.xml` (usually with a matching
   "What's New" section in `<description>`). The build writes the version and `sinceBuild` into
   the packaged `plugin.xml`.
2. After it is merged, tag that commit `v<x.y.z>`.
3. `./gradlew buildPlugin` builds `build/distributions/TornadoInsight-<x.y.z>.zip`; upload it to the
   JetBrains Marketplace and attach it to a GitHub release.
4. A follow-up commit sets `version` to the next `<x.y.z>-dev`.

## Conventions

- Commit subjects start with `[feature]`, `[fix]`, `[refactor]`, `[test]`, `[docs]`, `[build]` or
  `[release]`, followed by a bullet-point body.
- Pull requests are merged with a regular merge commit.
- User-visible text goes in the message bundle, not in code.
