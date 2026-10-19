package me.helvert.codebridge.workbench

import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Menu entries as an extension's manifest declares them, submenus included. */
class ExtensionMenusTest {

    private val extension = run {
        val manifest = """{ "publisher": "test", "name": "menus", "version": "1.0.0", "contributes": {
            "commands": [
                {"command": "test.open", "title": "Open"},
                {"command": "test.copy", "title": "Copy", "icon": "$(copy)"},
                {"command": "test.copyPath", "title": "Copy Path"}
            ],
            "submenus": [{"id": "test.copyMenu", "label": "Copy As", "icon": "$(files)"},
                         {"id": "test.loop", "label": "Loop"}],
            "menus": {
                "view/item/context": [
                    {"command": "test.copy", "group": "inline", "when": "viewItem == file"},
                    {"command": "test.open", "group": "navigation@1"},
                    {"submenu": "test.copyMenu", "group": "navigation@2"},
                    {"submenu": "test.loop"},
                    {"submenu": "test.undeclared"}
                ],
                "test.copyMenu": [
                    {"command": "test.copy"},
                    {"command": "test.copyPath", "when": "viewItem == file"}
                ],
                "test.loop": [{"submenu": "test.loop"}]
            }
        } }"""
        val parsed = requireNotNull(VsixManifestParser.parse(manifest).manifest) { "fixture manifest did not parse" }
        InstalledExtension(parsed, Files.createTempDirectory("codebridge-menus"), null, "2026-01-01T00:00:00Z")
    }

    private val entries = ExtensionMenus.entries("view/item/context", { _, value -> value }, listOf(extension))

    private fun context(item: String): (String) -> JsonPrimitive? = { key -> if (key == "viewItem") JsonPrimitive(item) else null }

    @Test
    fun `a submenu holds the entries of the menu it names, and an undeclared one is left out`() {
        assertEquals(listOf("Copy", "Open", "Copy As", "Loop"), entries.map { it.title })
        // Naming itself is not followed: it holds nothing, so it is never offered.
        assertEquals(emptyList(), entries.single { it.title == "Loop" }.children)
        val submenu = entries.single { it.title == "Copy As" }
        assertEquals("$(files)", submenu.icon)
        assertEquals(listOf("Copy", "Copy Path"), submenu.children?.map { it.title })
        val ids = entries.flatMap { it.flatten() }.map { it.id }
        assertEquals(ids.distinct(), ids, "Every entry, a submenu's included, has an id of its own")
    }

    @Test
    fun `a submenu offers what its entries' clauses allow, and goes with none`() {
        val forFile = ExtensionMenus.offered(entries, context("file"), "inline")
        assertEquals(listOf("Copy", "Open", "Copy As"), forFile.map { it.title }, "Inline first, then by group and order")
        assertEquals(listOf("Copy", "Copy Path"), forFile.single { it.submenu }.children?.map { it.title })

        val forFolder = ExtensionMenus.offered(entries, context("folder"), "inline")
        assertEquals(listOf("Open", "Copy As"), forFolder.map { it.title })
        assertEquals(listOf("Copy"), forFolder.single { it.submenu }.children?.map { it.title })

        val dto = forFile.single { it.submenu }.toDto(context("file"))
        assertEquals(listOf("Copy", "Copy Path"), dto.children.map { it.title })
    }

    @Test
    fun `an icon file named after a codicon the IDE draws is that codicon`() {
        // Gradle's title buttons: copies of codicons, shipped before menus took `$(codicon)`.
        val resources = Files.createDirectories(extension.directory.resolve("resources"))
        listOf("console.svg", "list-tree.svg", "gradle-elephant.svg").forEach {
            Files.writeString(
                resources.resolve(it),
                "<svg/>"
            )
        }
        val registered = mutableListOf<String>()
        fun image(path: String) =
            ExtensionMenus.image(extension, path) { file -> file.fileName.toString().also(registered::add) }

        assertEquals("$(console)", image("resources/console.svg"), "An alias of `terminal`, which the IDE draws")
        assertEquals("$(list-tree)", image("resources/list-tree.svg"))
        assertEquals("gradle-elephant.svg", image("resources/gradle-elephant.svg"), "An image of its own stays one")
        assertEquals(listOf("gradle-elephant.svg"), registered)
    }
}
