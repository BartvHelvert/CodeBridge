package me.helvert.codebridge.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which editor opens a file by default, as VS Code decides it. */
class CustomEditorTypesTest {
    private val drawio = CustomEditorTypeDto("drawio.editor", "Draw.io", "ext", listOf("*.drawio"), byDefault = true)
    private val inline = CustomEditorTypeDto("drawio.inline", "Draw.io Inline", "ext", listOf("*.drawio"), byDefault = false)
    private val hex = CustomEditorTypeDto("hexEditor.hexedit", "Hex Editor", "hex", listOf("*"), byDefault = false)
    private val types = listOf(drawio, inline, hex)

    @Test
    fun `without associations the first editor claiming the file by default opens it`() {
        val setup = CustomEditorSetupDto(types)
        assertEquals(drawio, CustomEditorTypes.defaultFor(setup, "a.drawio", "/p/a.drawio"))
        assertNull(CustomEditorTypes.defaultFor(setup, "a.txt", "/p/a.txt"))
    }

    @Test
    fun `an association picks an optional editor, or the text editor`() {
        assertEquals(inline, CustomEditorTypes.defaultFor(CustomEditorSetupDto(types, mapOf("*.drawio" to "drawio.inline")), "a.drawio", "/p/a.drawio"))
        assertEquals(hex, CustomEditorTypes.defaultFor(CustomEditorSetupDto(types, mapOf("*.txt" to "hexEditor.hexedit")), "a.txt", "/p/a.txt"))
        assertNull(CustomEditorTypes.defaultFor(CustomEditorSetupDto(types, mapOf("*.drawio" to CustomEditorTypes.TEXT)), "a.drawio", "/p/a.drawio"))
    }

    @Test
    fun `the most specific association wins, and one naming no editor is passed over`() {
        val setup = CustomEditorSetupDto(types, mapOf("*.drawio" to "default", "**/diagrams/*.drawio" to "drawio.inline", "*" to "gone.editor"))
        assertEquals(inline, CustomEditorTypes.defaultFor(setup, "a.drawio", "/p/diagrams/a.drawio"))
        assertNull(CustomEditorTypes.defaultFor(setup, "a.drawio", "/p/other/a.drawio"))
        assertNull(CustomEditorTypes.defaultFor(setup, "a.txt", "/p/a.txt"))
    }

    @Test
    fun `set default associates the extension, or the whole name without one`() {
        assertEquals("*.drawio", CustomEditorTypes.patternFor("sample.drawio"))
        assertEquals("*.svg", CustomEditorTypes.patternFor("a.drawio.svg"))
        assertEquals("Makefile", CustomEditorTypes.patternFor("Makefile"))
        assertEquals(".gitignore", CustomEditorTypes.patternFor(".gitignore"))
    }
}
