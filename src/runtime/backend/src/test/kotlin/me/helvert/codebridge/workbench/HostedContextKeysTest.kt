package me.helvert.codebridge.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `config.*` keys read the effective settings, however their names are nested. */
class HostedContextKeysTest {

    private fun settings(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `a setting is found flat or nested by its dots`() {
        val nested = settings("""{"rust-analyzer":{"showSyntaxTree":true,"check":{"command":"clippy"}}}""")
        assertEquals(JsonPrimitive(true), HostedContextKeys.setting(nested, "rust-analyzer.showSyntaxTree"))
        assertEquals(JsonPrimitive("clippy"), HostedContextKeys.setting(nested, "rust-analyzer.check.command"))

        val flat = settings("""{"rust-analyzer.showSyntaxTree":false}""")
        assertEquals(JsonPrimitive(false), HostedContextKeys.setting(flat, "rust-analyzer.showSyntaxTree"))

        // A section whose own name has a dot in it, as an extension's id often does.
        val dotted = settings("""{"lldb.dbg":{"verbose":true}}""")
        assertEquals(JsonPrimitive(true), HostedContextKeys.setting(dotted, "lldb.dbg.verbose"))

        assertNull(HostedContextKeys.setting(nested, "rust-analyzer.missing"))
    }
}
