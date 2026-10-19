package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `when` clauses as extensions write them in their menus and views. */
class WhenClauseTest {

    private val keys: Map<String, JsonElement> = mapOf(
        "view" to JsonPrimitive("lldb.excludedCallers"),
        "viewItem" to JsonPrimitive("frame.rust"),
        "debugType" to JsonPrimitive("lldb"),
        "inRustProject" to JsonPrimitive(true),
        "count" to JsonPrimitive(3),
        "lang" to JsonPrimitive("go"),
        "fileLang" to JsonPrimitive("rust"),
        "empty" to JsonPrimitive(""),
        "languages" to JsonArray(listOf(JsonPrimitive("rust"), JsonPrimitive("c"))),
    )

    private fun holds(clause: String?) = WhenClause.evaluate(clause, keys::get)

    @Test
    fun `no clause always holds`() {
        assertTrue(holds(null))
        assertTrue(holds("  "))
    }

    @Test
    fun `compares keys with quoted and bare values`() {
        assertTrue(holds("view == 'lldb.excludedCallers'"))
        assertTrue(holds("view == lldb.excludedCallers"))
        assertTrue(holds("debugType != 'python'"))
        assertFalse(holds("view == 'rustDependencies'"))
        assertTrue(holds("inRustProject == true"))
        assertTrue(holds("count > 2 && count <= 3"))
    }

    @Test
    fun `a key alone is its truthiness, and an unset key is false`() {
        assertTrue(holds("inRustProject"))
        assertFalse(holds("empty"))
        assertFalse(holds("neverSet"))
        assertTrue(holds("!neverSet"))
    }

    @Test
    fun `combines with precedence and parentheses`() {
        assertTrue(holds("neverSet || view == 'lldb.excludedCallers' && debugType == 'lldb'"))
        assertFalse(holds("(neverSet || inRustProject) && debugType == 'python'"))
        assertTrue(holds("!(debugType == 'python')"))
    }

    @Test
    fun `matches regular expressions and membership`() {
        assertTrue(holds("viewItem =~ /^frame\\./"))
        assertTrue(holds("viewItem =~ /FRAME/i"))
        assertFalse(holds("viewItem =~ /^module/"))
        assertTrue(holds("debugType not in languages"))
        assertFalse(holds("lang in languages"))
        assertTrue(holds("fileLang in languages"))
        assertTrue(holds("viewItem not in languages"))
    }

    @Test
    fun `a clause it cannot read is false`() {
        assertFalse(holds("view == 'unterminated"))
        assertFalse(holds("(view == 'x'"))
    }
}
