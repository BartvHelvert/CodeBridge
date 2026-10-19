import org.jetbrains.intellij.platform.gradle.TestFrameworkType

/**
 * The project's VS Code workspace files, on the machine they are on: which `.code-workspace` file the
 * project is opened with, its folders, `files.exclude`, and opening `launch.json`, `tasks.json` and
 * `settings.json`.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.rpc.backend")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
    implementation(project(":backend"))
    implementation(project(":shared"))
    implementation(project(":workspace.shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test { useJUnitPlatform() }
