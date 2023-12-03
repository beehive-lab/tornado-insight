// Main configuration for the TornadoVM IDEA plugin.

// Apply the required plugins for Java and IntelliJ IDEA development.
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.8.21"
    id("org.jetbrains.intellij") version "1.15.0"
}

// Define the group and version of the plugin.
group = "uk.ac.manchester.beehive.tornado.plugins"
version = "1.0"

// Define the repositories where dependencies can be fetched.
repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    // Set the version of IntelliJ IDEA that the plugin targets.
    version.set("2022.2.5")
    // Define the type of the IntelliJ Platform (IC = IntelliJ IDEA Community).
    type.set("IC") // Target IDE Platform
    // Specify any additional plugins this plugin depends on.
    plugins.set(listOf("com.intellij.java"))
}

dependencies {}

tasks {
    // Set the JVM compatibility versions
    // Ensure Java and Kotlin are both targeting JVM 17.
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }
    // Configure the plugin's compatibility range with IntelliJ IDEA builds.
    patchPluginXml {
        sinceBuild.set("222")
        untilBuild.set("232.*")
    }
    // Configure the plugin signing task.
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }
    // Configure the plugin publishing task.
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
