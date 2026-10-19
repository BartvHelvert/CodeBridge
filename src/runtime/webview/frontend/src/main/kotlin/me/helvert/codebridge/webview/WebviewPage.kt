package me.helvert.codebridge.webview

/**
 * The document a webview's browser loads: the extension's HTML, with what VS Code's webview host
 * adds in front of it -- the default styles and the theme's variables, and for a page that may run
 * scripts the script that gives it `acquireVsCodeApi` (`bootstrap.js`).
 *
 * Put at the very start of the head, ahead of the page's own content security policy: a policy in a
 * `<meta>` governs only what follows it, so the extension's nonce-only `script-src` does not refuse
 * the workbench's script, as it does not in VS Code. A page that may not run scripts is given a
 * policy saying so instead, which VS Code enforces by sandboxing its frame.
 */
internal object WebviewPage {

    private val DEFAULT_CSS: String = resource("default.css")
    private val BOOTSTRAP: String = resource("bootstrap.js")

    fun document(html: String, scripts: Boolean, bootstrap: String, theme: WebviewTheme): String {
        val added = buildString {
            // Served as bytes with no charset of their own; this one comes first, as it must.
            append("<meta charset=\"utf-8\">")
            append("<style id=\"_defaultStyles\">").append(theme.css).append('\n').append(DEFAULT_CSS).append("</style>")
            if (scripts) append("<script>").append(bootstrap).append("</script>")
            else append("<meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'none'\">")
        }
        val at = listOf(HEAD, HTML, DOCTYPE).firstNotNullOfOrNull { it.find(html) }?.range?.last?.plus(1) ?: 0
        return html.substring(0, at) + added + html.substring(at)
    }

    /**
     * `bootstrap.js`, given [send] -- the statement handing its `payload` to the workbench -- the
     * [state] the page last saved, as JSON, and the [theme] to start with. With [binary], the
     * `ArrayBuffer`s in the page's messages are sent as buffers, apart from the JSON. [load] is the
     * page's load, which it names when it says it is ready.
     */
    fun bootstrap(send: String, state: String?, theme: WebviewTheme, binary: Boolean = false, load: Int = 0): String =
        BOOTSTRAP.replace("/*SEND*/", send).replace("/*THEME*/null", script(theme.json)).replace("/*STATE*/undefined", state?.let(::script) ?: "undefined")
            .replace("/*BINARY*/false", binary.toString()).replace("/*LOAD*/0", load.toString())

    /** JSON as a script expression inside a `<script>` element, which a `</script>` in a string would end. */
    private fun script(json: String) = json.replace("<", "\\u003c")

    private val HEAD = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HTML = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    private fun resource(name: String): String =
        checkNotNull(WebviewPage::class.java.getResourceAsStream("/webview/$name")) { "Missing /webview/$name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
