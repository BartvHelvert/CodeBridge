import org.jetbrains.intellij.platform.gradle.TestFrameworkType

/**
 * The `vscode.tests` API drawn in the IDE: a run configuration whose console is the IDE's own test
 * runner, and gutter icons on the tests extensions declare.
 *
 * A module of its own because the test runner is a bundled plugin (`intellij.testRunner.plugin`)
 * rather than part of the core, as the TextMate support is: the backend keeps the tests, this draws
 * them, and the backend loads without it.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        // `DefaultDebugExecutor`, a content module of its own from 2026.3.
        bundledModule("intellij.platform.debugger")
        bundledModule("intellij.platform.testRunner")
        bundledModule("intellij.platform.smRunner")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
    implementation(project(":backend"))
    implementation(project(":protocol"))
    implementation(project(":shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnitPlatform()
    // As in the backend, only what is needed: this plugin, and the test runner it draws in.
    systemProperty("idea.load.plugins.id", "me.helvert.codebridge,intellij.testRunner.plugin")
}
