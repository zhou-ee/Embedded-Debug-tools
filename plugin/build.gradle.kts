import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension
    intellijPlatform {
        val clionHome = providers.gradleProperty("clion.home")
            .orNull
            ?: System.getenv("CLION_HOME")
            ?: listOf(
                "E:/Software/JetBrains IDE/CLion",
                "C:/Program Files/JetBrains/CLion 2026.2",
                "C:/Program Files/JetBrains/CLion 2024.3",
                "C:/Program Files/JetBrains/CLion",
                "/Applications/CLion.app/Contents"
            ).firstOrNull { File(it).isDirectory }

        if (clionHome != null && File(clionHome).isDirectory) {
            local(clionHome)
        } else {
            clion("2024.3")
        }
        testFramework(TestFrameworkType.Platform)
    }
}

