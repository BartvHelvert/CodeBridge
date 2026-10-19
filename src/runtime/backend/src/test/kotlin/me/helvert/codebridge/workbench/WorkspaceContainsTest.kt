package me.helvert.codebridge.workbench

import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `workspaceContains:` as it decides whether a host is started for an extension at all.
 *
 * The bug: rust-analyzer activates on nothing else, so in a project with only it installed no host
 * ever started -- the host would have activated it, finding `Cargo.toml`, had anything started one.
 */
class WorkspaceContainsTest {

    private val root: Path = Files.createTempDirectory("codebridge-workspace-contains")

    private fun contains(vararg events: String, timeout: Duration = 5.seconds) =
        HostedWorkspace.containsAny(CodeBridgeWorkspace.single(root), events.toList(), timeout)

    @Test
    fun `a name is looked up in the folder`() {
        root.resolve("Cargo.toml").writeText("")

        assertTrue(contains("workspaceContains:Cargo.toml"))
        assertFalse(contains("workspaceContains:rust-project.json"))
    }

    @Test
    fun `a name may reach into a subfolder`() {
        root.resolve(".vscode").createDirectories().resolve("settings.json").writeText("{}")

        assertTrue(contains("workspaceContains:.vscode/settings.json"))
    }

    @Test
    fun `a glob is searched for, at the depth it names`() {
        root.resolve("crate").createDirectories().resolve("Cargo.toml").writeText("")

        assertTrue(contains("workspaceContains:*/Cargo.toml"))
        assertFalse(contains("workspaceContains:Cargo.toml"), "A name is the folder's own file, not one below it")
        assertFalse(contains("workspaceContains:*/*/Cargo.toml"))
        assertTrue(contains("workspaceContains:**/Cargo.toml"))
    }

    @Test
    fun `other activation events are not workspace patterns`() {
        root.resolve("Cargo.toml").writeText("")

        assertFalse(contains("onLanguage:rust", "onCommand:Cargo.toml"))
    }

    @Test
    fun `a search that runs out of time counts as found`() {
        // As VS Code counts it: an extension is better started for nothing than never started.
        root.resolve("a").createDirectories().resolve("b.txt").writeText("")

        assertTrue(contains("workspaceContains:**/absent.json", timeout = Duration.ZERO))
    }
}
