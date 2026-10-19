package me.helvert.codebridge.manifest

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VsixManifestParserTest {

    @Suppress("SameParameterValue")
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** The real published manifest of vadimcn.vscode-lldb 1.12.3, taken from Open VSX. */
    private val codelldb: VsixManifest by lazy {
        val result = VsixManifestParser.parse(fixture("codelldb-package.json"))
        assertEquals(emptyList(), result.problems)
        assertNotNull(result.manifest)
    }

    @Test
    fun `reads extension identity`() {
        assertEquals("vadimcn.vscode-lldb", codelldb.id)
        assertEquals("vadimcn", codelldb.publisher)
        assertEquals("1.12.3", codelldb.version)
        assertEquals("^1.61.0", codelldb.engineRange)
        assertEquals("extension.js", codelldb.main)
        assertTrue(codelldb.activationEvents.contains("onDebug"))
    }

    @Test
    fun `an extension with an entry point is not declarative only`() {
        assertTrue(!codelldb.isDeclarativeOnly)
    }

    @Test
    fun `reads the debugger contribution`() {
        val debugger = assertNotNull(codelldb.contributes.debugger("lldb"))
        assertEquals("CodeLLDB", debugger.label)
        assertEquals(setOf("launch", "attach"), debugger.requestKinds)
        assertContains(debugger.languages, "rust")
        assertContains(debugger.languages, "cpp")
    }

    @Test
    fun `keeps configurationAttributes verbatim as a schema for launch json`() {
        val debugger = assertNotNull(codelldb.contributes.debugger("lldb"))
        val launch = assertNotNull(debugger.attributesFor("launch"))
        val properties = assertNotNull(launch["properties"])
        val keys = (properties as kotlinx.serialization.json.JsonObject).keys
        assertContains(keys, "program")
        assertContains(keys, "args")
        assertContains(keys, "cwd")
        assertContains(keys, "env")
        // attach exposes a different property set - this is what drives per-request completion.
        val attach = assertNotNull(debugger.attributesFor("attach"))
        assertContains((attach["properties"] as kotlinx.serialization.json.JsonObject).keys, "pid")
    }

    @Test
    fun `codelldb cannot be launched from the manifest alone`() {
        val debugger = assertNotNull(codelldb.contributes.debugger("lldb"))
        val discovery = assertIs<AdapterDiscovery.RequiresExtensionHost>(debugger.adapterDiscovery)
        assertContains(discovery.reason, "DebugAdapterDescriptorFactory")
    }

    @Test
    fun `exposes the command indirection used by launch json variables`() {
        val debugger = assertNotNull(codelldb.contributes.debugger("lldb"))
        assertEquals("lldb.pickProcess", debugger.variables["pickProcess"])
    }

    @Test
    fun `reports contribution points we do not handle`() {
        val ignored = codelldb.contributes.ignoredKeys
        assertContains(ignored, "commands")
        assertContains(ignored, "menus")
        assertTrue("debuggers" !in ignored)
    }

    @Test
    fun `a debugger declaring a program can be launched without javascript`() {
        val result = VsixManifestParser.parse(
            """
            {
              "publisher": "acme", "name": "thin-debug", "version": "0.1.0",
              "contributes": { "debuggers": [ {
                  "type": "thin", "label": "Thin",
                  "runtime": "node", "program": "./out/adapter.js", "args": ["--server"]
              } ] }
            }
            """.trimIndent()
        )
        val debugger = assertNotNull(result.manifest?.contributes?.debugger("thin"))

        val discovery = assertIs<AdapterDiscovery.Declared>(debugger.adapterDiscovery)
        assertEquals("./out/adapter.js", discovery.program)
        assertEquals("node", discovery.runtime)
        assertEquals(listOf("--server"), discovery.args)
    }

    @Test
    fun `a manifest without an entry point is declarative only`() {
        val result = VsixManifestParser.parse(
            """{ "publisher": "acme", "name": "theme", "version": "1.0.0" }"""
        )
        assertTrue(assertNotNull(result.manifest).isDeclarativeOnly)
    }

    @Test
    fun `a malformed debugger does not cost the others`() {
        val result = VsixManifestParser.parse(
            """
            {
              "publisher": "acme", "name": "x", "version": "1.0.0",
              "contributes": { "debuggers": [ { "label": "No type" }, { "type": "good" } ] }
            }
            """.trimIndent()
        )
        assertEquals(listOf("good"), assertNotNull(result.manifest).contributes.debuggers.map { it.type })
        assertEquals(1, result.problems.size)
    }

    @Test
    fun `a language icon is read as the pair VS Code requires`() {
        val result = VsixManifestParser.parse(
            """
            {
              "publisher": "acme", "name": "x", "version": "1.0.0",
              "contributes": { "languages": [
                { "id": "paired", "icon": { "light": "icons/light.png", "dark": "icons/dark.png" } },
                { "id": "half", "icon": { "light": "icons/light.png" } },
                { "id": "none" }
              ] }
            }
            """.trimIndent()
        )

        val languages = assertNotNull(result.manifest).contributes.languages.associateBy { it.id }
        assertEquals(LanguageIcon("icons/light.png", "icons/dark.png"), languages.getValue("paired").icon)
        // Half a pair is as likely to be a typo as an intention, and VS Code requires both.
        assertEquals(null, languages.getValue("half").icon)
        assertEquals(null, languages.getValue("none").icon)
    }

    @Test
    fun `a package json that is not an extension is rejected with a reason`() {
        val result = VsixManifestParser.parse("""{ "name": "just-an-npm-package", "version": "1.0.0" }""")
        assertEquals(null, result.manifest)
        assertContains(result.problems.single(), "publisher")
    }
}
