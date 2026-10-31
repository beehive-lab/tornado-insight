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

Some problems have a quick fix. Press **Alt+Enter** on:
- an `assert` statement, or a system or native call whose result is not used, to remove it;
- a `throw` statement in a `void` kernel, to remove it;
- a `try` statement, to remove the `try/catch` and keep the body;
- a `throws` clause, to remove it;
- a non-`void` kernel's return type, to make the kernel return `void`.

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

### AI-assisted transformation (experimental)
In a module that uses the TornadoVM API, right-click a plain Java method and choose
**Transform into TornadoVM Kernel (AI)...**, or press Alt+Enter on its name. It is off until you set up a model in [Configure](#configure).

A dialog first asks which TornadoVM API to transform it into:
- **Readable: Loop Parallel API**: the method's loops, with `@Parallel` on the independent ones
  (and `@Reduce` for reductions). The kernel still runs as plain Java when called directly.
- **Kernel API**: a `KernelContext` kernel with explicit threads, work-groups, local memory and
  barriers. It declares its launch grid in comments at the start of its body (`// grid: n` and
  optionally `// local: 16`); the preview says how to launch it with a `GridScheduler`.
- **Tile API**: a `TileContext` kernel that works on whole tiles of elements. It needs TornadoVM
  7.0.0 or newer, a method whose arrays are TornadoVM arrays (`FloatArray`, `IntArray`, ...), and
  an NVIDIA GPU as TornadoVM's default device, with the CUDA backend.
- **Fastest (measured)**: transforms the method into each API it can, runs and times each verified
  kernel on arrays of the size you choose (1M elements by default) and recommends one. The Loop
  Parallel kernel is recommended unless another is faster by more than a threshold (20% by
  default). It can take several minutes and makes up to three model requests per API.

An API the project cannot use is not offered, and the dialog says why: for example, a TornadoVM
API without `TileContext`.

TornadoInsight checks each answer: it must parse, its names must resolve, it must keep the
method's parameters (plus one result parameter if the method returns a value), be `static` and
`void`, and pass the static checks above. A `@Parallel` placed on the method instead of the loop is
moved to the loop. Problems go back to the model, for up to three answers. An answer cut off at the
output limit is reported as such, not sent back to the model.

TornadoVM does not check that a loop's iterations are independent: a `@Parallel` loop or kernel
whose iterations depend on each other (such as `y[i] = a * x[i] + y[i - 1]`) runs, but computes
wrong results. The model is asked to answer that such a method cannot be parallelised, and why;
TornadoInsight then reports that, and offers no kernel.

With TornadoVM and a JDK 21+ project JDK, you can tick **Run each kernel on this machine** in the
dialog: each kernel that passes the checks is then compiled and run with TornadoVM, and compared
with the original method, which also runs, on two synthetic data sets: arrays of 4096 values from
0.5 to 1.5, then of 4097 (an odd length, which no work-group size divides) values from -1 to 1 with
zeros. Integers and 2D or 3D data follow the method's shape. The kernel must run on the device: one
TornadoVM runs in Java instead (a bailout) does not pass. A kernel that fails or gives different
results goes back to the model; if the last answer still fails or gives different results, no
kernel is offered and the failure or difference is reported. This runs code the model wrote, with your user's rights:
a kernel may use only the device side of the TornadoVM API (its types, `TornadoMath`, `KernelContext`
and `TileContext`), `Math`, the primitive wrappers and your project's code, and no strings. The
project methods it calls and the fields it uses are copied with it and held to the same rules, and
its grid may use only its parameters and arithmetic. The model's code runs without your
`ANTHROPIC_API_KEY` in its environment. Running is off by default; **Fastest (measured)**
turns it on, since it times the kernels. The project JDK is checked against your TornadoVM SDK first,
as for dynamic inspection; if they do not fit, the kernels are shown as not run, and why.

A diff then shows the result. You choose whether **Accept** adds the kernel beside the method, as
`<name>Kernel`, leaving the method and its callers as they are, or replaces the method with it,
keeping its name, access modifier and doc comment; Undo restores the original. A Kernel API or Tile
API kernel does one thread's part of the work and must be launched from a TaskGraph, so the preview
warns that the method's calls stop working if it replaces the method. Replacing is the default for
every API. If a replacing kernel changes the signature, the preview says how many calls must be
updated. The transformation runs in the background and can be cancelled; you can keep editing
meanwhile, and the preview warns if the method itself was changed. Transformations run one at a
time, in the order they were started, since a local server may answer one request at a time and
timings need the GPU to themselves: the others wait in the status bar.

## Getting started

### Requirements
- IntelliJ IDEA 2023.1 or newer (or another JetBrains IDE with Java support)
- A [TornadoVM](https://github.com/beehive-lab/TornadoVM) SDK 2.2.0 or newer, for dynamic inspection
  and for running transformed kernels (TornadoVM 6.0.0 and newer need TornadoInsight 1.6.0 or newer;
  the Tile API needs TornadoVM 7.0.0 or newer)
- For AI-assisted transformation, a local server with the OpenAI chat API, or a Claude API key
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

*Settings | Other Settings | TornadoInsight | AI-Assisted Transformation* sets up the model that
transforms Java methods into TornadoVM kernels. It is off until you enable it, because it sends code
to the model:
- **Local server**: any server with the OpenAI chat API, such as
  jitllm, llama.cpp, vLLM, Ollama or LM Studio. The default URL is jitllm's,
  `http://localhost:8090/v1`; the others serve by default at `http://localhost:8080/v1`
  (llama.cpp), `:8000/v1` (vLLM), `:11434/v1` (Ollama) and `:1234/v1` (LM Studio). **Max answer
  tokens** (8192 by default) limits each answer: a kernel takes 1K to 3K tokens, so the limit
  mostly stops a model that rambles; raise it for a model that reasons before it answers. An
  answer cut off at the limit is reported, not sent back. The code stays on your machine or network; a plain `http://`
  URL to another machine is flagged, since the code and the key would cross the network unencrypted.
- **Claude API**: Anthropic's API, model `claude-opus-5-5` by default. The key is kept in the IDE
  password store; if none is set, `ANTHROPIC_API_KEY` is used. The code is sent to Anthropic.

Besides the code, the model is told the name and TornadoVM backend of the device the kernel will
run on and, when kernels are run, the compiler errors, mismatches and part of TornadoVM's output
for each failure, with your directories replaced by names such as `$TORNADOVM_HOME` and `~`.

**Test Connection** checks the server and the key, and lists the models a local server serves.

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
