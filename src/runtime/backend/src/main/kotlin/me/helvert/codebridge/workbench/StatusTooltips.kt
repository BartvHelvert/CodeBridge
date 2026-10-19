package me.helvert.codebridge.workbench

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import me.helvert.codebridge.exthost.protocol.payload.StatusBar
import me.helvert.codebridge.language.HoverMarkdown
import me.helvert.codebridge.rpc.StatusTooltipActionDto
import me.helvert.codebridge.rpc.StatusTooltipBlockDto

/**
 * A status bar item's tooltip as VS Code's hover shows it, made into HTML for the frontend's.
 *
 * Markdown is rendered as hovers are, by a Markdown parser, so links are found as VS Code finds
 * them; what VS Code adds to Markdown for its hovers is then done to the HTML: `command:` links the
 * extension trusted run, numbered, `$(icons)` are drawn, and images take a size after `|` in their
 * address -- GitLens's avatar, `![](uri|width=16,height=16)`.
 *
 * The result is then cut into [StatusTooltipBlockDto]s, so that a column of command links is drawn
 * as IntelliJ draws a list of actions, and a rule as a separator.
 */
internal object StatusTooltips {

    /** The tooltip's [html], and the commands its `codebridge-status:N` links run, by `N`. */
    class Rendered(val html: String?, val commands: List<StatusBar.Command>) {
        /** [html] as the frontend draws it. */
        val blocks: List<StatusTooltipBlockDto>? by lazy { html?.let(::blocks) }
    }

    fun render(project: Project, entry: StatusBar.Entry): Rendered {
        val tooltip = entry.tooltip ?: return Rendered(null, emptyList())
        if (!entry.markdown) return Rendered(plain(tooltip), emptyList())
        val html = runCatching { HoverMarkdown.toHtml(project, tooltip) }.getOrNull()
            ?: return Rendered(plain(tooltip), emptyList())
        return links(images(if (entry.themeIcons) themeIcons(html) else html), entry::trusts)
    }

    /**
     * [html] in blocks: a rule at the top level is a separator, and two or more paragraphs in a row
     * that each hold nothing but one `codebridge-status:` link -- an icon and a label -- are a list of
     * actions. Everything else is HTML as it was, GitLens's row of icon links among it: those share
     * a paragraph, and are left where the extension put them.
     */
    internal fun blocks(html: String): List<StatusTooltipBlockDto> {
        val blocks = mutableListOf<StatusTooltipBlockDto>()
        val pending = StringBuilder()
        val actions = mutableListOf<Pair<String, StatusTooltipActionDto>>()

        fun flushHtml() {
            if (pending.isNotBlank()) blocks += StatusTooltipBlockDto(html = pending.toString().trim())
            pending.setLength(0)
        }

        fun flushActions() {
            if (actions.size >= MIN_ACTIONS) {
                flushHtml()
                blocks += StatusTooltipBlockDto(actions = actions.map { it.second })
            } else actions.forEach { pending.append(it.first) }
            actions.clear()
        }

        for (element in topLevel(html)) {
            val action = action(element)
            when {
                action != null -> actions += element to action
                element.isBlank() -> if (actions.isEmpty()) pending.append(element)
                RULE.matches(element.trim()) -> {
                    flushActions(); flushHtml()
                    // Two rules in a row, or one at either end, separate nothing.
                    if (blocks.isNotEmpty() && !blocks.last().separator) blocks += StatusTooltipBlockDto(separator = true)
                }

                else -> {
                    flushActions(); pending.append(element)
                }
            }
        }
        flushActions(); flushHtml()
        if (blocks.lastOrNull()?.separator == true) blocks.removeAt(blocks.lastIndex)
        return blocks
    }

    /** [html] cut between its top-level elements, the text between them kept as pieces of its own. */
    private fun topLevel(html: String): List<String> {
        val pieces = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (tag in TAG.findAll(html)) {
            val name = TAG_NAME.find(tag.value)?.groupValues?.get(1)?.lowercase() ?: continue
            val closing = tag.value.startsWith("</")
            val void = tag.value.endsWith("/>") || name in VOID
            if (depth == 0 && !closing && tag.range.first > start) {
                pieces += html.substring(start, tag.range.first)
                start = tag.range.first
            }
            when {
                closing -> depth = maxOf(0, depth - 1)
                !void -> depth++
            }
            if (depth == 0) {
                pieces += html.substring(start, tag.range.last + 1)
                start = tag.range.last + 1
            }
        }
        if (start < html.length) pieces += html.substring(start)
        return pieces
    }

    /** The action [paragraph] is, if all it holds is one command link. */
    private fun action(paragraph: String): StatusTooltipActionDto? {
        val match = ACTION.matchEntire(paragraph.trim()) ?: return null
        val label = StringUtil.unescapeXmlEntities(match.groupValues[4]).trim().ifEmpty { return null }
        return StatusTooltipActionDto(
            index = match.groupValues[1].toInt(),
            label = label,
            icon = match.groupValues[3].ifEmpty { null },
            title = TITLE.find(match.groupValues[2])?.groupValues?.get(1)?.let(StringUtil::unescapeXmlEntities),
        )
    }

    /**
     * [html] with each `command:` link whose command [trusts] allows pointed at its place among the
     * commands, `codebridge-status:N`, and any other reduced to its label, as an inert link reads in VS Code.
     * Other links -- to the web -- are left as they are.
     */
    internal fun links(html: String, trusts: (String) -> Boolean): Rendered {
        val commands = mutableListOf<StatusBar.Command>()
        val linked = ANCHOR.replace(html) { anchor ->
            val href =
                StringUtil.unescapeXmlEntities(anchor.groupValues[1]).removePrefix(HoverMarkdown.INTERNAL_LINK_PREFIX)
            if (!href.startsWith("command:")) return@replace anchor.value
            val command = StatusBar.linkCommand(href)
            if (command == null || !trusts(command.id)) return@replace anchor.groupValues[3]
            commands += command
            """<a href="${StatusBar.LINK_SCHEME}${commands.size - 1}"${anchor.groupValues[2]}>${anchor.groupValues[3]}</a>"""
        }
        return Rendered(linked, commands)
    }

    /**
     * [html] with each `$(name)` in its text drawn as that icon, as VS Code draws them in a
     * `MarkdownString` that `supportThemeIcons` -- in inline code too, which is how GitLens badges a
     * commit, `$(git-commit) 25a20ef` -- but not in a code block, nor inside a tag's attributes.
     */
    internal fun themeIcons(html: String): String {
        var block = 0
        var start = 0
        return buildString {
            for (tag in TAG.findAll(html)) {
                val text = html.substring(start, tag.range.first)
                append(if (block > 0) text else icons(text))
                append(tag.value)
                when {
                    tag.value.startsWith("<pre", ignoreCase = true) -> block++
                    tag.value.startsWith("</pre", ignoreCase = true) -> block = maxOf(0, block - 1)
                }
                start = tag.range.last + 1
            }
            val rest = html.substring(start)
            append(if (block > 0) rest else icons(rest))
        }
    }

    /**
     * `<img src="uri|width=16,height=16">` as `<img src="uri" width="16" height="16">`; the
     * renderer may have written the `|` as `%7C`.
     */
    internal fun images(html: String): String = IMAGE_SIZE.replace(html) { image ->
        val size = image.groupValues[3].split(',').mapNotNull { part ->
            val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            value.trim().toIntOrNull()?.let { name.trim() to it }
        }.filter { it.first == "width" || it.first == "height" }
        """<img${image.groupValues[1]}src="${image.groupValues[2]}"""" + size.joinToString("") { (name, value) -> """ $name="$value"""" }
    }

    private fun icons(text: String) = THEME_ICON.replace(text) { """<icon src="codicon:${it.groupValues[1]}"/>""" }

    private fun plain(text: String) = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")

    private val ANCHOR = Regex("""<a href="([^"]*)"([^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("""<[^>]*>""")
    private val TAG_NAME = Regex("""^</?([A-Za-z][A-Za-z0-9]*)""")
    private val VOID = setOf("br", "hr", "img", "icon", "input", "wbr")
    private val RULE = Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE)
    private val ACTION = Regex(
        """<p>\s*<a href="${Regex.escape(StatusBar.LINK_SCHEME)}(\d+)"([^>]*)>\s*(?:<icon src="(codicon:[^"]+)"/>)?([^<]*)</a>\s*</p>"""
    )
    private val TITLE = Regex("""\stitle="([^"]*)"""")

    /** Fewer action paragraphs than this are left in the text: one alone reads fine as a link. */
    private const val MIN_ACTIONS = 2
    private val THEME_ICON = Regex("""\$\(([A-Za-z0-9-]+(?:~[a-z]+)?)\)""")
    private val IMAGE_SIZE = Regex("""<img([^>]*?\s)src="([^"|]*?)(?:\||%7[Cc])((?:width|height)=[^"]*)"""")
}
