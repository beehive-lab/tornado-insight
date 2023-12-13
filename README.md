# TornadoInsight: Unleashing the Power of TornadoVM in IntelliJ IDEA
<img align="left" width="250" height="250" src="etc/figures/TornadoInsight.jpg">

**TornadoInsight** is an open source IntelliJ plugin 
that aims to enhance the experience of developers
when working with [TornadoVM](https://github.com/beehive-lab/TornadoVM).
It provides  a built-in on-the-fly static checker, empowering developers 
to identify unsupported Java features in TornadoVM
 and understand the reasons behind these limitations. 
 Additionally, it introduces a dynamic testing framework 
 that enables developers to easily test individual TornadoVM tasks. 
 It automatically wraps TornadoVM tasks, invoking the native 
 TornadoVM runtime on the developers' machines for seamless debugging and testing. 

## Key Features:
### 1. On-the-Fly Static Checker
TornadoInsight is equipped with an on-the-fly static checker. 
This tool scans TornadoVM code in real time, pinpointing any 
Java features that are not supported by TornadoVM. Through 
instant notifications, developers gain immediate insights into 
potential compatibility issues, allowing for proactive adjustments 
and adherence to the TornadoVM guidelines. Currently, the static checker 
performs checks for datatypes, Traps/Exceptions, recursion, native
method calls, assert statements. 

TornadoInsight provides a tool window to view detailed information emerging from 
the static inspection of TornadoInsight.

### 2. Dynamic Testing Framework
TornadoInsight simplifies the testing process for individual TornadoVM tasks. 
After creating a TornadoVM task, there is no need to write the main method
or initialize the method parameters. You only need to select the method in 
the tool window of TornadoInsight to test it. 

With its dynamic testing framework, developers can seamlessly conduct tests on
specific tasks within their codebase. TornadoInsight dynamically generates a
test file and automatically generates the Main method and the 
TaskGraph required by TornadoVM. In turn, it automatically creates and initializes 
the input/output variables according to the parameter types. Then, TornadoInsight invokes the 
TornadoVM runtime on the developer's machine to run the generated Java class.
This functionality streamlines the debugging process, making it convenient 
for developers to identify and resolve issues in their TornadoVM applications.
If a TornadoVM task is compatible with TornadoVM, the test will output the generated OpenCL
kernel code for it, so that developers can test it. If it is not compatible, 
it will output an exception. In addition, the elapsed time for running the checks
is displayed in the bottom right corner.

## How to use TornadoInsight?
This section covers the initial steps that are required in order to correctly setup the plugin project inside the IntelliJ IDE.

### Folder layout

The output of this folder is as follows:

```
 idea
   |-src
     |-main
       |-java (plugin sources)
       |-resources (plugin resources - the plugin.xml file lives here)
   |-build (where build files are stored)
       |-distributions (where the plugin zip file is generated)   
   |-build.gradle (the gradle build file)
   |-gradle.properties (contains properties required to build this project)
```

### Building the plugin


This plugin can be built with `gradle`. To build the plugin, simply run the following command from the `idea` folder:

`sh gradlew clean build`
This will download gradle and the required IntelliJ dependencies,
will build the plugin and will place the results in
the `build/distributions` folder.
Once the build is configured correctly, the plugin can even be tested in a sandbox environment, as follows:

`sh gradlew runIde`

### Installing the plugin

To install the plugin in your IDE, first you need to build a plugin module file (a `.zip` file), as described in the previous section.

Once the plugin zip has been obtained, it can be installed in the IDE; go in `Help -> Find Action...`, and type `Install plugin from disk`, and then select the corresponding action from the drop down list. A new file dialog will pop up: point the IDE to the zip file you have created in the step above. The IDE will require a restart - once restart is completed the installation process is completed, and the plugin is ready to be used to run and debug jtreg tests.

## Using TornadoInsight


### Pre-requisites
TornadoInsight invokes at runtime Java and TornadoVM on the developers' local machine, and therefore, developers need to make sure that they have installed those prior to using the plugin.
- [TornadoVM](https://github.com/beehive-lab/TornadoVM) >= 1.0
- JDK >= 21

### Configuring TornadoInsight
The dynamic inspection feature of TornadoInsight is configured after the installation of the plugin, as follows:
- Navigate to "Preferences" or "Settings" depending on your operating system.
- Select "TornadoInsight" from the menu.

Developers should configure the TornadoVM root directory (i.e. the path to the TornadoVM cloned repository) and select a JDK which should be >= JDK 21. 
Additionally, developers should indicate a tentative "array size" that can be used by TornadoInsight to  set the size of the input and output arrays of a TornadoVM task.

## Limitations
#### 1. No support for non-JDK method invocations
TornadoInsight, in its current state, lacks support for dynamic
inspection of references. This limitation arises due to the nature
of the dynamically generated Java code during the dynamic inspection
process. The Java code is derived from a copy of the Tornado task method,
and in this copying process, automatic handling of non-JDK method
invocations and global variable replacements is not performed.

#### 2.No support for TornadoVM Kernel API
The [TornadoVM Kernel API](https://tornadovm.readthedocs.io/en/latest/programming.html#kernel-api) is another way to express compute-kernels in TornadoVM.
TornadoInsight does not support the Kernel API because it currently uses the
[Loop Parallel API](https://tornadovm.readthedocs.io/en/latest/programming.html#loop-parallel-api): `@Parallel` and  `@Reduce` to locate TornadoVM Tasks.


## Acknowledgments

This work is partially supported by the following EU & UKRI grants (most recent first):
- EU Horizon Europe & UKRI [INCODE 101093069](https://incode-project.eu/).
- EU Horizon Europe & UKRI [AERO 101092850](https://aero-project.eu/).
- EU Horizon Europe & UKRI [ENCRYPT 101070670](https://encrypt-project.eu).
- EU Horizon Europe & UKRI [TANGO 101070052](https://tango-project.eu).
- EU Horizon 2020 [ELEGANT 957286](https://www.elegant-h2020.eu/).

## License
[![License: Apache 2](https://img.shields.io/badge/License-Apache%202.0-red.svg)](https://github.com/beehive-lab/tornadovm-intellij-plugin/blob/main/LICENSE)
