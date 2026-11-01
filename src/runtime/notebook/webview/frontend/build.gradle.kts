/**
 * Notebook outputs only a browser can draw -- HTML with scripts, and SVG -- each a page in the IDE's
 * browser. A module of its own because that browser is a plugin (Web Browser (JCEF),
 * `com.intellij.modules.jcef`), as for `webview.frontend`: without it, `notebook.frontend` draws
 * those outputs as well as the IDE's own components can.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
        bundledPlugin("com.intellij.modules.jcef")
        bundledModule("intellij.platform.ui.jcef")
        bundledModule("intellij.libraries.jcef")
    }
    implementation(project(":notebook.frontend"))
}
