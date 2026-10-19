package me.helvert.codebridge.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a page is given ahead of the extension's own HTML. */
class WebviewPageTest {
    private val theme = WebviewTheme(mapOf("--vscode-foreground" to "#ffffff"), "vscode-dark", "Dark")

    @Test
    fun `puts the workbench's script ahead of the page's own content security policy`() {
        val html = """<!DOCTYPE html><html lang="en"><head><meta http-equiv="Content-Security-Policy" content="script-src 'nonce-x'"></head><body></body></html>"""
        val page = WebviewPage.document(html, scripts = true, bootstrap = "BOOT", theme = theme)
        assertTrue(page.startsWith("""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><style id="_defaultStyles">:root { --vscode-foreground: #ffffff; }"""))
        assertTrue(page.indexOf("<script>BOOT</script>") < page.indexOf("Content-Security-Policy\" content=\"script-src 'nonce-x'"))
    }

    @Test
    fun `forbids scripts to a page that may not run them`() {
        val page = WebviewPage.document("<p>Hi</p>", scripts = false, bootstrap = "BOOT", theme = theme)
        assertFalse("BOOT" in page)
        assertTrue(page.contains("""content="script-src 'none'""""))
        assertTrue(page.endsWith("<p>Hi</p>"))
    }

    @Test
    fun `finds the head whatever its case and attributes`() {
        val page = WebviewPage.document("<HTML><HEAD id=h><title>t</title></HEAD></HTML>", scripts = false, bootstrap = "", theme = theme)
        assertTrue(page.startsWith("<HTML><HEAD id=h><meta charset"))
    }

    @Test
    fun `hands the bootstrap its state and theme, safe inside a script element`() {
        val script = WebviewPage.bootstrap("SEND(payload);", """{"a":"</script>"}""", theme)
        assertTrue("SEND(payload);" in script)
        assertTrue("let state = {\"a\":\"\\u003c/script>\"};" in script)
        assertTrue("\"kind\":\"vscode-dark\"" in script)
        assertFalse("/*STATE*/" in script)
        assertEquals(true, "let state = undefined;" in WebviewPage.bootstrap("", null, theme))
    }

    @Test
    fun `tells the bootstrap whether to send a message's buffers apart`() {
        assertTrue("const binary = false;" in WebviewPage.bootstrap("", null, theme))
        assertTrue("const binary = true;" in WebviewPage.bootstrap("", null, theme, binary = true))
        assertFalse("/*BINARY*/" in WebviewPage.bootstrap("", null, theme))
    }

    @Test
    fun `tells the bootstrap which load it is, for it to name when it is ready`() {
        val script = WebviewPage.bootstrap("", null, theme, load = 7)
        assertTrue("const load = 7;" in script)
        assertTrue("send('r' + load);" in script)
    }
}
