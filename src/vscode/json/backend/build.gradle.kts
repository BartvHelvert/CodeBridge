import org.jetbrains.intellij.platform.gradle.TestFrameworkType

/**
 * Schemas for `launch.json` and `settings.json`, kept apart so only this module requires the JSON
 * plugin: without it there is no JSON editing to complete, and the module is not loaded.
 */
dependencies {
    intellijPlatform {
        bundledPlugin("com.intellij.modules.json")
        bundledModule("intellij.json.backend")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
    implementation(project(":backend"))
    implementation(project(":shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("idea.load.plugins.id", "me.helvert.codebridge.runtime,me.helvert.codebridge,com.intellij.modules.json")
}
