package me.helvert.codebridge.status

import me.helvert.codebridge.icons.Codicons

/**
 * Status bar text as VS Code writes it, read into something Swing can draw.
 *
 * VS Code puts icons inline, as `$(name)` references to its codicons, and animates one with a
 * `~spin` suffix: rust-analyzer's item reads `$(loading~spin) rust-analyzer` while it is busy.
 * The references are lifted out of the text and drawn, in order, as the label's icon.
 */
internal object StatusText {

    data class Parsed(val label: String, val icons: List<Codicons.Reference>)

    fun parse(text: String): Parsed =
        Parsed(Codicons.strip(text).replace(WHITESPACE, " ").trim(), Codicons.references(text))

    private val WHITESPACE = Regex("""\s+""")
}
