# How to Develop and Test the TornadoInsight

## Prerequisites
Developers need to make sure they installed the following prior to developing TornadoInsight.

- [TornadoVM](https://github.com/beehive-lab/TornadoVM) >= 1.0
- JDK >= 21
- IntelliJ IDEA >= 2022.2


## Setting Up the Development Environment
### 1. Fork the Repository
- Go to the repository page on GitHub.
- Click on the "Fork" button at the top right to create your own copy of the repository.

### 2. Clone the Repository
```
git clone <forked-repository-url>
```

### 3. Setup IntelliJ

- Open IntelliJ IDEA.
- Click on **File** -> **Open** and click on the _TornadoInsight_ directory.
- Go to **File** -> **Project Structure** and ensure SDK is set to JDK 21.
- Go to **File** -> **Settings** -> **Build, Execution, Development** -> **Build Tools** -> **Gradle** and ensure Gradle JVM is set to 21

## Extending the Plugin

### 1. Make Your Changes
- Modify the plugin to add new features or fix bugs.

### 2. Test Your Changes in a Sandbox Environment
- Open the Gradle tool window.
- Navigate to **TornadoInsight** -> **Tasks** -> **intellij**
- Click on **runIDE** to start a new instance of IntelliJ with TornadoInsight loaded.
- Test the plugin and make sure it works as expected.

## Pushing Changes and Open a Pull Request

### Commit and push your changes
- Navigate to **Git** -> **Commit** and select the changes you would like to commit.
- Write a commit message and click on **Commit and Push**.
- In the new window, click on **Push**.

### Open a Pull Request
- Go to your forked repository on GitHub.
- Click on **Contribute** -> **Open pull request**
- Add the title and description for your pull request
- Click on **Create pull request**