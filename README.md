# TornadoInsight: Unleashing the Power of TornadoVM in IntelliJ IDEA
<img align="left" width="250" height="250" src="etc/figures/TornadoInsight.jpg">

**TornadoInsight** is an open source IntelliJ plugin that makes it easier to develop
[TornadoVM](https://github.com/beehive-lab/TornadoVM) applications. It checks TornadoVM kernels
for unsupported Java features as you type, runs individual kernels on your TornadoVM installation
to show what TornadoVM generates for them, and writes TornadoVM boilerplate for you through live
templates.

TornadoInsight is available on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/23309-tornadoinsight):
install it from *Settings | Plugins | Marketplace* in IntelliJ IDEA.

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/23309-tornadoinsight?label=JetBrains%20Marketplace)](https://plugins.jetbrains.com/plugin/23309-tornadoinsight)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/23309-tornadoinsight)](https://plugins.jetbrains.com/plugin/23309-tornadoinsight)

The plugin is described in *Dynamic and Static Code Analysis for Java Programs on Heterogeneous
Hardware* (MPLR '25, [doi:10.1145/3759426.3760984](https://doi.org/10.1145/3759426.3760984)).

<br clear="left"/>

## Features

### Static checks
While you edit a TornadoVM kernel (a method with `@Parallel`/`@Reduce` loops, or one that takes a
`KernelContext` or `TileContext`), TornadoInsight underlines the Java features TornadoVM cannot
compile and explains why in a tooltip. It checks the kernel and the helper methods it calls:

| Check | Flags |
|---|---|
| Data type | Variables, fields and parameters of types TornadoVM cannot compile to device code |
| Recursion | Direct and indirect recursion |
| Method throw | `throw`, `try`/`catch` and methods that declare exceptions |
| Assert statement | `assert` statements |
| System call | Native methods, and JDK system, I/O and reflection classes (e.g. `System`, `Thread`, `java.io`) |
| External library | Calls into classes outside the JDK and the TornadoVM API |
| Static TaskGraph | `TaskGraph` instances held in `static` fields |
| Non-void return type | Kernel methods that return a value instead of `void` |
| Unanalysable helper | Calls to helpers that cannot be checked: unresolved, native, or without source |

The checks are listed under *Settings | Editor | Inspections | TornadoVM*, and the **Inspector Guide**
tab of the TornadoVM tool window explains each restriction.

### Dynamic inspection
Some problems only show up when TornadoVM compiles and runs a kernel. Open the **TornadoVM** tool
window, select one or more of the listed kernels, and click **Run**. TornadoInsight generates a small
test program around each kernel (with test data sized by *Max array size*), compiles it, and runs it
on your TornadoVM installation. The **TornadoInsight Console** shows whether the kernel ran and the
kernel code TornadoVM generated for it (e.g. OpenCL C), or the error it hit.

The tool window lists only kernels that pass the static checks.

<img src="https://github.com/user-attachments/assets/9273b26b-92b4-4cbb-8669-ae28717e6afc" alt="A dynamic inspection result: the Run button in the TornadoVM tool window, the generated kernel in the TornadoInsight Console, and the notification with the inspection time" width="800">

### Live templates
TornadoInsight ships live templates that write the TornadoVM boilerplate for you. Type an
abbreviation in a Java editor and press <kbd>Tab</kbd>; then press <kbd>Tab</kbd> again to move
between the fields to fill in. The **Live Templates** tab of the TornadoVM tool window lists them,
with a preview of the code each one inserts.

| Abbreviation | Inserts | Where it works |
|---|---|---|
| `tg` | A `TaskGraph` that transfers inputs, runs a task and transfers outputs back | Inside a method body |
| `tep` | A `TornadoExecutionPlan` for a `TaskGraph`, in a try-with-resources block | Inside a method body |
| `tparallel` | A kernel method with a `@Parallel` loop | In a class body |
| `tkernel` | A kernel method that takes a `KernelContext` | In a class body |
| `ttile` | A kernel method that takes a `TileContext` | In a class body (TornadoVM 7.0.0+) |

For example, `tg` inserts:

```java
TaskGraph taskGraph = new TaskGraph("s0")
        .transferToDevice(DataTransferMode.FIRST_EXECUTION, INPUTS)
        .task("t0", CLASS::METHOD, ARGS)
        .transferToHost(DataTransferMode.EVERY_EXECUTION, OUTPUTS);
```

The templates are offered in modules that use the TornadoVM API, and in any Java project when
`TORNADOVM_HOME` points at a TornadoVM SDK. You can edit them in
*Settings | Editor | Live Templates | TornadoVM*.

## Getting started

### Requirements
- IntelliJ IDEA 2023.1 or newer (or another JetBrains IDE with Java support)
- A [TornadoVM](https://github.com/beehive-lab/TornadoVM) SDK 2.2.0 or newer, for dynamic inspection
  (TornadoVM 6.0.0 and newer need TornadoInsight 1.6.0 or newer)
- A JDK 21 or newer as the project JDK, matching the requirement of your TornadoVM SDK; a standard
  OpenJDK build, not a GraalVM one

### Install
In *Settings | Plugins | Marketplace*, search for **TornadoInsight**, install it and restart the IDE.
The plugin's page is [plugins.jetbrains.com/plugin/23309-tornadoinsight](https://plugins.jetbrains.com/plugin/23309-tornadoinsight).

You can install TornadoVM with [SDKMAN!](https://sdkman.io) (`sdk install tornadovm`) or from
[tornadovm.org/downloads](https://www.tornadovm.org/downloads).

### Point TornadoInsight at TornadoVM
TornadoInsight finds TornadoVM through the `TORNADOVM_HOME` environment variable, which must be
visible to the IDE process. IDEs started from a launcher, the Dock or JetBrains Toolbox do not read
shell files such as `~/.bashrc`, so set it as follows (`which tornado` shows where your SDK is):

- **Linux (systemd):** create `~/.config/environment.d/tornado.conf` containing
  `TORNADOVM_HOME=/absolute/path/to/tornado-sdk`, then log out and back in.
- **macOS:** run `launchctl setenv TORNADOVM_HOME /absolute/path/to/tornado-sdk`, then quit the IDE
  (Cmd+Q) and start it again.
- **Windows:** add a user variable `TORNADOVM_HOME=C:\path\to\tornado-sdk` under
  *System Properties | Environment Variables*, then restart the IDE.

### Configure
*Settings | Other Settings | TornadoInsight* has one setting, **Max array size**: the length of the
arrays (and the size of the other inputs) that dynamic inspection creates as test data. Dynamic
inspection uses the project JDK from *File | Project Structure*.

## Limitations
- **Dynamic inspection runs a copy of the kernel.** The generated test program contains a copy of
  the kernel, the helper methods it calls and the fields they use, from any class of the project.
  Kernels that use other project classes as types (as parameters, objects or enum constants), or
  that call libraries other than the JDK and TornadoVM, cannot be inspected dynamically.

## Developing TornadoInsight
Building the plugin, running it in a sandbox IDE, the code layout, the tests and the developer tools
are described in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Acknowledgments

This work is partially supported by the following EU & UKRI grants (most recent first):
- EU Horizon Europe & UKRI [P2CODE 101093069](https://p2code-project.eu/).
- EU Horizon Europe & UKRI [AERO 101092850](https://aero-project.eu/).
- EU Horizon Europe & UKRI [ENCRYPT 101070670](https://encrypt-project.eu).
- EU Horizon Europe & UKRI [TANGO 101070052](https://tango-project.eu).
- EU Horizon 2020 [ELEGANT 957286](https://www.elegant-h2020.eu/).

## License
[![License: Apache 2](https://img.shields.io/badge/License-Apache%202.0-red.svg)](LICENSE)
