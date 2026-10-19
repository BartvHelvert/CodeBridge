package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Selectors as the host serializes them: which provider claims which file. */
class DocumentSelectorTest {

    private fun matches(selector: String, language: String = "rust", path: String = "/c:/work/src/main.rs") =
        DocumentSelector.matches(Json.parseToJsonElement(selector), language, path)

    @Test
    fun `matches by language and scheme`() {
        assertTrue(matches($$"""[{"$serialized":true,"language":"rust","scheme":"file"}]"""))
        assertFalse(matches("""[{"language":"python","scheme":"file"}]"""))
        assertFalse(matches("""[{"language":"rust","scheme":"untitled"}]"""))
        assertTrue(matches("""[{"language":"python"},{"language":"rust"}]"""))
        assertTrue(matches("""["rust"]"""))
        assertTrue(matches("""[{"language":"*"}]"""))
        assertFalse(matches("[]"))
    }

    @Test
    fun `notebook cells never match a file`() {
        assertFalse(matches("""[{"language":"rust","notebookType":"jupyter-notebook"}]"""))
    }

    @Test
    fun `matches glob patterns`() {
        assertTrue(matches("""[{"pattern":"**/*.rs"}]"""))
        assertTrue(matches("""[{"pattern":"*.{rs,toml}"}]"""))
        assertFalse(matches("""[{"pattern":"**/*.py"}]"""))
        assertTrue(matches("""[{"pattern":"**/src/*.rs"}]"""))
        assertFalse(matches("""[{"pattern":"**/tests/*.rs"}]"""))
        assertTrue(matches("""[{"pattern":"**/Cargo.toml"}]""", path = "/c:/work/Cargo.toml"))
        assertTrue(matches("""[{"pattern":"ma?n.[a-z]s"}]"""))
        assertTrue(matches("""[{"pattern":"**/*.RS"}]"""))
    }

    @Test
    fun `matches a pattern relative to its base`() {
        val base = """{"scheme":"file","authority":"","path":"/c:/work","query":"","fragment":""}"""
        assertTrue(matches("""[{"pattern":{"baseUri":$base,"pattern":"src/**"}}]"""))
        assertFalse(matches("""[{"pattern":{"baseUri":$base,"pattern":"tests/**"}}]"""))
        assertFalse(matches("""[{"pattern":{"baseUri":$base,"pattern":"src/**"}}]""", path = "/c:/other/src/main.rs"))
    }

    @Test
    fun `a selector it cannot read is taken to match`() {
        assertTrue(DocumentSelector.matches(null, "rust", "/x"))
        assertTrue(matches("""[{"language":"rust","pattern":["**/*.rs"]}]"""))
    }
}
