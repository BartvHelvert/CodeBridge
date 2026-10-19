package me.helvert.codebridge.icons

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import kotlin.test.*

/** Codicons as IntelliJ icons: the IDE's own where it has one, VS Code's otherwise. */
class CodiconsTest {

    @Test
    fun `references are read out of text and can be taken out of it`() {
        val text = "$(loading~spin) rust-analyzer $(check)"

        assertEquals(listOf(Codicons.Reference("loading", spin = true), Codicons.Reference("check", spin = false)), Codicons.references(text))
        assertEquals(" rust-analyzer ", Codicons.strip(text))
    }

    @Test
    fun `references in HTML become icons drawn as the codicons they name`() {
        val html = Codicons.htmlIcons("$(loading~spin) Loading...")

        assertEquals("""<icon src="codicon:loading~spin"/> Loading...""", html)
        assertSame(AnimatedIcon.Default.INSTANCE, Codicons.iconOf("codicon:loading~spin"))
        assertSame(AllIcons.General.Settings, Codicons.iconOf("codicon:gear"))
        assertNull(Codicons.iconOf("data:image/png;base64,"))
    }

    @Test
    fun `a codicon the IDE has an icon for looks native`() {
        assertSame(AllIcons.Actions.Restart, Codicons.icon("debug-restart"))
        assertSame(AllIcons.General.Settings, Codicons.icon("gear"))
        // Java Projects' JRE System Library and the jars under it.
        assertSame(AllIcons.Nodes.PpLibFolder, Codicons.icon("folder-library"))
        assertSame(AllIcons.FileTypes.Archive, Codicons.icon("file-zip"))
        // An alias is drawn as what it is an alias of: `console` is `terminal`.
        assertSame(AllIcons.Debugger.Console, Codicons.icon("console"))
    }

    @Test
    fun `any spinning codicon is the IDE's spinner`() {
        assertSame(AnimatedIcon.Default.INSTANCE, Codicons.icon(Codicons.Reference("sync", spin = true)))
    }

    @Test
    fun `a codicon the IDE has no icon for is VS Code's own`() {
        assertNull(Codicons.known("rocket"))
        assertNotSame(Codicons.DEFAULT, Codicons.icon("rocket"))
        // An alias only VS Code knows: `microscope` is `beaker`.
        assertNotNull(Codicons.bundled("microscope"))
    }

    @Test
    fun `a name that is no codicon, nor contributed by an extension, is the default icon`() {
        assertSame(Codicons.DEFAULT, assertIs<ContributedIcons.Contributed>(Codicons.icon("unheard-of")).current)
        assertNull(Codicons.known("unheard-of"))
        // An extension's name never reaches outside the codicons.
        assertNull(Codicons.bundled("../fileicons/seti/livescript"))
    }
}
