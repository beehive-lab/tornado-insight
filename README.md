# TornadoInsight: Unleashing the Power of TornadoVM in IntelliJ IDEA

**TornadoInsight** is an open source IntelliJ plugin 
that aims to enhance the experience of developers
when working with [TornadoVM](https://github.com/beehive-lab/TornadoVM). TornadoInsight is designed 
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
it will output an exception. In addition, test run times are displayed in 
the lower right corner to allow developers to evaluate performance.

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
TornadoInsight invokes Java and TornadoVM on the developer's local machine as it works, and you need to make sure that you have them installed correctly before using the plugin.
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
[Kernel API](Loop Parallel API) is another way to express compute-kernels in TornadoVM.
TornadoInsight does not support the Kernel API because it currently uses the
[Loop parallel API]( @reduce ): `@Parallel` and  `@Reduce` to locate TornadoVM Tasks.