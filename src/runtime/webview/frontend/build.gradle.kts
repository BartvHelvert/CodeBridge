/**
 * Webviews drawn in the IDE: extensions' panels as editor tabs and their webview views as tool
 * window tabs, each a page in the IDE's own browser.
 *
 * A module of its own because that browser is a plugin (Web Browser (JCEF), `com.intellij.modules.jcef`)
 * rather than part of the core, as the test runner is for `testrunner.backend`: the backend keeps the webviews,
 * this draws them, and the frontend loads without it -- saying, where a webview view would be, why
 * there is none.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
        bundledPlugin("com.intellij.modules.jcef")
        bundledModule("intellij.platform.ui.jcef")
        bundledModule("intellij.libraries.jcef")
    }
    implementation(project(":frontend"))
    implementation(project(":shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
