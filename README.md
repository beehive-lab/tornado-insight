# TornadoInsight: Unleashing the Power of TornadoVM in IntelliJ IDEA

**TornadoInsight** is an open source IntelliJ plugin 
that aims to enhance the experience of developers
when working with TornadoVM. TornadoInsight is designed 
exclusively for TornadoVM development. It provides 
a built-in on-the-fly static checker, empowering developers 
to identify unsupported Java features in TornadoVM
 and understand the reasons behind these limitations. 
 Additionally, TornadoInsight introduces a dynamic testing framework 
 that enables developers to easily test individual TornadoVM tasks. 
 It automatically wraps TornadoVM tasks, invoking the native 
 TornadoVM runtime on the developers' machine for seamless debugging and testing. 

## Key Features:
### 1. On-the-Fly Static Checker
TornadoInsight comes equipped with an on-the-fly static checker. 
This tool scans TornadoVM code in real time, pinpointing any 
Java features that are not supported by TornadoVM. Through 
instant notifications, developers gain immediate insights into 
potential compatibility issues, allowing for proactive adjustments 
and adherence to TornadoVM guidelines. Currently, the static checker 
already has checks for datatype, Traps/Exceptions, recursion, native
method calls, assert statements. 

TornadoInsight provides a tool window to view the built-in 
static inspector for detailed information.

### 2. Dynamic Testing Framework
TornadoInsight simplifies the testing process for individual TornadoVM tasks. 
After creating a TornadoVM Task, there is no need to write the main method
or initialize the method parameters. You only need to select the method in 
the tool window of TornadoInsight to test it. 

With its dynamic testing framework, developers can seamlessly conduct tests on
specific tasks within their codebase. TornadoInsight will dynamically generate a
test file and guide the automatic generation of the Main method and the 
Taskgraph required by TornadoVM, And automatically create and initialize 
variables according to parameter types. Then TornadoInsight invokes the 
TornadoVM runtime on the developer's machine to run the generated Java file.
This functionality streamlines the debugging process, making it convenient 
for developers to identify and resolve issues in their TornadoVM applications.
If TornadoVM Task is compatible with TornadoVM, the test outputs the OpenCL
kernel code for it, so that developers can debug it. If it is not compatible, 
it will output an exception. In addition, test run times are displayed in 
the lower right corner to allow developers to evaluate performance.

## How to use TornadoInsight?
### 1. Installation

Getting started with TornadoInsight is a straightforward process:

- Open IntelliJ IDEA.
- Navigate to "Preferences" or "Settings" depending on your operating system.
- Select "Plugins" from the menu.
- Search for "TornadoInsight" and click "Install."

### 2. Pre-requisites
TornadoInsight invokes Java and TornadoVM on the developer's local machine as it works, and you need to make sure that you have them installed correctly before using the plugin.
- TornadoVM >= 0.17
- JDK >= 21

### 3.Configuring TornadoInsight
The dynamic inspection feature of TornadoInsight is configured after the installation of the plugin, as follows:
- Navigate to "Preferences" or "Settings" depending on your operating system.
- Select "TornadoInsight" from the menu.

Developers should configure the TornadoVM root directory (i.e. the path to the TornadoVM cloned repository) and the path to `JAVA_HOME` which should be >= JDK 21. 
Additionally, developers should indicate a tentative "array size" that can be used by TornadoInsight to  set the size of the input and output arrays of a TornadoVM task.


