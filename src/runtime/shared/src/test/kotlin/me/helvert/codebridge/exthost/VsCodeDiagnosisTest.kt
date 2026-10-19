package me.helvert.codebridge.exthost

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins how an installation is judged, without needing VS Code present.
 *
 * The point of a diagnosis rather than a nullable installation is that every way of getting this
 * wrong produces a different, actionable message. A silent `null` sent the user hunting through
 * logs for a connection timeout that really meant "you pointed at the wrong folder".
 */
class VsCodeDiagnosisTest {

    @Test
    fun `reports a missing path`() {
        val absent = createTempDirectory("vscode-probe").resolve("nowhere")
        val diagnosis = VsCodeInstallation.inspect(absent)
        assertIs<VsCodeInstallation.Diagnosis.NoSuchPath>(diagnosis)
        assertTrue(diagnosis.message.contains("nothing at"), diagnosis.message)
    }

    @Test
    fun `reports a directory that is not VS Code`() {
        val empty = createTempDirectory("vscode-probe")
        val diagnosis = VsCodeInstallation.inspect(empty)
        assertIs<VsCodeInstallation.Diagnosis.NotAnInstallation>(diagnosis)
        assertTrue(diagnosis.message.contains("not a VS Code installation"), diagnosis.message)
    }

    @Test
    fun `accepts a complete installation and reads its version`() {
        val root = fakeInstallation()
        val diagnosis = VsCodeInstallation.inspect(root)
        assertIs<VsCodeInstallation.Diagnosis.Usable>(diagnosis)
        assertEquals("1.132.1", diagnosis.found.version)
        assertEquals(root, diagnosis.found.root)
    }

    @Test
    fun `names the file that makes an installation unusable`() {
        val root = fakeInstallation()
        // Startup aborts on a missing NLS bundle long before the host reaches our pipe.
        Files.delete(root.resolve("resources/app/out/nls.messages.json"))

        val diagnosis = VsCodeInstallation.inspect(root)
        assertIs<VsCodeInstallation.Diagnosis.Incomplete>(diagnosis)
        assertEquals(listOf("nls.messages.json"), diagnosis.missing.map { it.fileName.toString() })
        assertTrue(diagnosis.message.contains("nls.messages.json"), diagnosis.message)
    }

    @Test
    fun `finds the app directory in a commit-named subdirectory`() {
        // How current Windows builds lay themselves out: the launcher sits beside per-build folders.
        val root = createTempDirectory("vscode-probe")
        Files.createFile(root.resolve("Code.exe"))
        populateApp(root.resolve("c2d1b13fdc/resources/app"), version = "1.133.0")

        val diagnosis = VsCodeInstallation.inspect(root)
        assertIs<VsCodeInstallation.Diagnosis.Usable>(diagnosis)
        assertEquals("1.133.0", diagnosis.found.version)
    }

    @Test
    fun `recognises a remote extension host package`() {
        // The shippable runtime: `out/` at the root beside the node that runs it, no resources/app
        // and no Electron. Detected before the desktop layout because it is the flatter of the two.
        val root = createTempDirectory("reh-probe")
        Files.createFile(root.resolve("node.exe"))
        populateApp(root, version = "1.135.06055")

        val diagnosis = VsCodeInstallation.inspect(root)
        assertIs<VsCodeInstallation.Diagnosis.Usable>(diagnosis)
        assertEquals(VsCodeInstallation.Kind.SERVER, diagnosis.found.kind)
        assertEquals(root, diagnosis.found.appDirectory)
        assertEquals(
            ExtensionHostLaunchSpec.Runtime.NODE,
            diagnosis.found.launchSpec("pipe", 1).runtime,
        )
    }

    @Test
    fun `a configured location that does not work is reported, never worked around`() {
        // Falling back -- to a discovered install, or to the managed runtime -- would run a VS Code
        // the user did not choose, and the resulting API mismatch is much harder to diagnose than
        // being told the configured path is wrong.
        val wrong = createTempDirectory("vscode-probe")
        val diagnosis = VsCodeInstallation.resolve(wrong)
        assertIs<VsCodeInstallation.Diagnosis.NotAnInstallation>(diagnosis)
        assertEquals(null, diagnosis.installation)
    }

    @Test
    fun `a configured location is used as given`() {
        val root = fakeInstallation()
        assertEquals(root, VsCodeInstallation.resolve(root).installation?.root)
    }

    private fun fakeInstallation(version: String = "1.132.1"): Path {
        val root = createTempDirectory("vscode-probe")
        Files.createFile(root.resolve("Code.exe"))
        populateApp(root.resolve("resources/app"), version)
        return root
    }

    private fun populateApp(app: Path, version: String) {
        app.resolve("out/vs/workbench/api/node").createDirectories()
        app.resolve("out/bootstrap-fork.js").writeText("")
        app.resolve("out/nls.messages.json").writeText("{}")
        app.resolve("out/vs/workbench/api/node/extensionHostProcess.js").writeText("")
        app.resolve("package.json").writeText("""{"version":"$version"}""")
        app.resolve("product.json").writeText("""{"nameLong":"Visual Studio Code","commit":"abc"}""")
    }
}
