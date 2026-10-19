/**
 * The Terminal tool window's half of extension terminals, on the frontend.
 *
 * A module of its own so that only it requires the Terminal plugin: without the plugin it is not
 * loaded, nobody listens for terminal requests, and the backend keeps extension terminals in Run
 * tool window tabs instead.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")
    }

    implementation(project(":shared"))
}
