import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

/**
 * CodeBridge Bundled Extensions: what a plugin made from a VS Code extension depends on. It reads the extension
 * from that plugin and hands it to the runtime, which it depends on in turn; nothing of VS Code
 * itself -- the marketplace, `.vscode` -- comes with it, as CodeBridge brings that.
 *
 * No code of its own, only the descriptor; the module brings the Kotlin.
 */
plugins {
    java
    id("org.jetbrains.intellij.platform")
}

sourceSets.main {
    resources.setSrcDirs(listOf("resources"))
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.idea.get())

        localPlugin(project(":runtime"))
        // Not transitive: the module builds on the runtime's, which must not be packed in here too.
        pluginModule(implementation(project(":bundled.backend")) { isTransitive = false })
    }
}

intellijPlatform {
    // The distribution's name: `CodeBridgeBundled-<version>.zip`, one for every platform.
    projectName = "CodeBridgeBundled"

    pluginVerification {
        ides {
            current()
        }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
    }
}

/** The plugin's licence and the third-party notices it is distributed under, at its root. */
tasks.withType<BuildPluginTask>().configureEach {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
}
