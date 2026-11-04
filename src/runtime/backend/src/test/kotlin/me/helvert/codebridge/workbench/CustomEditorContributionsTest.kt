package me.helvert.codebridge.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** `contributes.customEditors` as an extension's manifest declares them. */
class CustomEditorContributionsTest {

    @Test
    fun `reads each editor's files and whether it opens them by default`() {
        val manifest = Json.parseToJsonElement(
            """{"contributes": {"customEditors": [
                {"viewType": "hexEditor.hexedit", "displayName": "Hex Editor", "priority": "option",
                 "selector": [{"filenamePattern": "*"}]},
                {"viewType": "drawio.editor", "displayName": "Draw.io",
                 "selector": [{"filenamePattern": "*.drawio"}, {"filenamePattern": "*.dio"}]},
                {"viewType": "untitled.only", "displayName": "Opened by its extension alone"},
                {"displayName": "No view type", "selector": [{"filenamePattern": "*.x"}]}
            ]}}"""
        ).jsonObject
        val types = CustomEditorContributions.types("me.ext", manifest)
        assertEquals(listOf("hexEditor.hexedit", "drawio.editor"), types.map { it.viewType })
        assertEquals(listOf(false, true), types.map { it.byDefault })
        assertEquals(listOf("*.drawio", "*.dio"), types[1].patterns)
        assertEquals("me.ext", types[1].extensionId)
        assertEquals("Draw.io", types[1].displayName)
    }

    @Test
    fun `reads the editor associations, leaving out any that names no editor`() {
        val settings = Json.parseToJsonElement(
            """{"workbench.editorAssociations": {"*.drawio": "drawio.inline", "*.txt": "default", "*.x": 3}}"""
        ).jsonObject
        assertEquals(mapOf("*.drawio" to "drawio.inline", "*.txt" to "default"), CustomEditorContributions.associations(settings))
    }
}
