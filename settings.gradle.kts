import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
    plugins {
        id("rpc") version "2.4.0-RC-0.1"
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.19.0"
}

rootProject.name = "CodeBridge"

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}

// CodeBridge Runtime, under src/runtime: the plugin other plugins depend on, the extension host and
// what draws what extensions do. `runtime` itself is the plugin, built from src/runtime/plugin.
include("runtime")

include("protocol")
include("shared")
include("frontend")
include("backend")

// Feature modules are named for the side they load on, `.backend` or `.frontend`, as the platform
// names its own; one without a side (`protocol`, `shared`) loads on both.

// Isolates the bundle provider and reload listener from the backend when TextMate is disabled.
include("textmate.backend")

// Extension terminals in the Terminal tool window, kept apart so only it requires the Terminal plugin.
include("terminal.frontend")

// Extensions' tests in the IDE's test runner, kept apart so only it requires the Test Runner plugin.
include("testrunner.backend")

// What only the platform's internal API allows, kept apart so a published build can leave it out.
include("internal.backend")

// Webviews in the IDE's browser, kept apart so only it requires the Web Browser (JCEF) plugin.
include("webview.frontend")

// CodeBridge Bundled Extensions, under src/bundled: what plugins made from a VS Code extension depend on, which
// hands their extensions to the runtime. `bundled` itself is the plugin, built from src/bundled/plugin.
include("bundled")
include("bundled.backend")

// CodeBridge's own, under src/vscode: what it adds on the runtime to make the IDE work like VS Code.
// The root project is that plugin, its descriptor in src/vscode/plugin.

// launch.json and settings.json schemas, kept apart so only it requires the JSON plugin.
include("json.backend")

// Browsing and installing from Open VSX. Dotted names, so the content modules
// read `CodeBridge.marketplace.*`; a nested `:marketplace:frontend` would share its coordinates
// (`me.helvert:frontend`) with `:frontend`.
for (side in listOf("shared", "backend", "frontend")) {
    include("marketplace.$side")
}

// What makes the project's VS Code workspace files apply: `.vscode/` and `.code-workspace` files,
// opened, chosen and excluded by. Without it the extensions see the project directory alone.
for (side in listOf("shared", "backend", "frontend")) {
    include("workspace.$side")
}

// Opt-in split-mode validation. Not a plugin module: it builds nothing and ships nothing, it only
// launches a backend and a JetBrains Client and asks the client what it drew.
include("ui-test")

/** CodeBridge's own modules; every other module is the runtime's. */
val vscodeModules = setOf("json.backend") +
        listOf("marketplace", "workspace").flatMap { group -> listOf("shared", "backend", "frontend").map { "$group.$it" } }

// Every subproject lives under src/, in its plugin's directory, a dot in its name standing for a
// directory: `marketplace.backend` is src/vscode/marketplace/backend.
for (module in rootProject.children) {
    module.projectDir = file(
        when (module.name) {
            "runtime" -> "src/runtime/plugin"
            "bundled" -> "src/bundled/plugin"
            "bundled.backend" -> "src/bundled/backend"
            "ui-test" -> "src/ui-test"
            in vscodeModules -> "src/vscode/" + module.name.replace('.', '/')
            else -> "src/runtime/" + module.name.replace('.', '/')
        }
    )
}
