package me.helvert.codebridge.ide

import me.helvert.codebridge.extensions.ExtensionInstallationListener
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.ProjectExtensions
import me.helvert.codebridge.workbench.CodeBridgeWorkspaceChoice
import me.helvert.codebridge.workspace.WorkspaceChoices
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An extension disabled in one workspace, as VS Code disables one. */
class ProjectExtensionsTest : EditorFixture() {

    @BeforeEach
    fun installProbe() = onEdt {
        ExtensionInstallationService.getInstance().install(vsix()) {}
        // A light fixture can share its project, and with it this state, between tests.
        projectExtensions().setDisabled(ID, false)
    }

    @AfterEach
    fun uninstallProbe() = onEdt {
        choose(WorkspaceChoices.Choice.Undecided)
        projectExtensions().loadState(ProjectExtensions.Disabled())
        ExtensionInstallationService.getInstance().uninstall(ID)
    }

    @Test
    fun `an extension disabled here is left out of what the project runs, and its host is told`() = onEdt {
        var told = 0
        fixture.project.messageBus.connect(fixture.testRootDisposable)
            .subscribe(ProjectExtensions.CHANGED, ExtensionInstallationListener { told++ })
        val revision = projectExtensions().changes.value
        assertTrue(ID in projectExtensions().enabled().map { it.id })

        assertTrue(projectExtensions().setDisabled("Probe.Disabled-Here", true), "Ids are matched whatever their case")
        assertFalse(ID in projectExtensions().enabled().map { it.id })
        // Still installed and enabled everywhere else.
        assertTrue(ExtensionInstallationService.getInstance().list().single { it.id == ID }.enabled)
        assertEquals(1, told)
        assertEquals(revision + 1, projectExtensions().changes.value)

        assertFalse(projectExtensions().setDisabled(ID, true), "Nothing changed, so nothing restarts")
        assertEquals(1, told)

        assertTrue(projectExtensions().setDisabled(ID, false))
        assertTrue(ID in projectExtensions().enabled().map { it.id })
        assertEquals(2, told)
    }

    @Test
    fun `what is disabled here is kept in the workspace file`() = onEdt {
        projectExtensions().setDisabled(ID, true)
        assertEquals(listOf(ProjectExtensions.Workspace("", listOf(ID))), projectExtensions().state.workspaces)
        val storage =
            ProjectExtensions::class.java.getAnnotation(com.intellij.openapi.components.State::class.java).storages
        assertEquals(com.intellij.openapi.components.StoragePathMacros.WORKSPACE_FILE, storage.single().value)
    }

    @Test
    fun `each workspace has its own list, the folder alone included`() = onEdt {
        projectExtensions().setDisabled(ID, true)
        val revision = projectExtensions().changes.value

        choose(WorkspaceChoices.Choice.File("other.code-workspace"))
        assertFalse(projectExtensions().isDisabled(ID), "Another workspace starts with nothing disabled")
        assertTrue(ID in projectExtensions().enabled().map { it.id })
        assertTrue(projectExtensions().changes.value > revision, "What draws from the list hears of the switch")

        choose(WorkspaceChoices.Choice.FolderOnly)
        assertTrue(projectExtensions().isDisabled(ID), "The folder alone is the workspace it was disabled in")
    }

    @Test
    fun `a list kept for the whole project moves to the workspace chosen`() = onEdt {
        choose(WorkspaceChoices.Choice.File("team.code-workspace"))
        projectExtensions().loadState(ProjectExtensions.Disabled(ids = listOf(ID)))

        assertTrue(projectExtensions().isDisabled(ID))
        assertEquals(
            ProjectExtensions.Disabled(listOf(ProjectExtensions.Workspace("team.code-workspace", listOf(ID)))),
            projectExtensions().state,
        )
    }

    private fun choose(choice: WorkspaceChoices.Choice) {
        WorkspaceChoices.choose(Path.of(fixture.project.basePath!!), choice)
        fixture.project.messageBus.syncPublisher(CodeBridgeWorkspaceChoice.TOPIC).workspaceChosen()
    }

    private fun projectExtensions() = ProjectExtensions.getInstance(fixture.project)

    private fun vsix(): Path {
        val manifest = """{"publisher":"probe","name":"disabled-here","version":"1.0.0","main":"main.js",""" +
                """"engines":{"vscode":"^1.90.0"}}"""

        @Suppress("HttpUrlsUsage") // an XML namespace, not a link
        val xml = """<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">""" +
                """<Metadata><Identity Id="disabled-here"/></Metadata></PackageManifest>"""
        val path = Files.createTempFile(Files.createTempDirectory("codebridge-project-extensions"), "probe-", ".vsix")
        ZipArchiveOutputStream(path).use { zip ->
            for ((name, text) in listOf(
                "extension/package.json" to manifest,
                "extension/main.js" to "module.exports = {}",
                "extension.vsixmanifest" to xml,
            )) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(text.toByteArray())
                zip.closeArchiveEntry()
            }
        }
        return path
    }

    private companion object {
        const val ID = "probe.disabled-here"
    }
}
