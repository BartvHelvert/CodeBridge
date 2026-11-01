package me.helvert.codebridge.completion

import com.intellij.icons.AllIcons
import javax.swing.Icon

/** How VS Code draws and places a completion, wherever one is drawn: in a file, or in a notebook cell. */
object VsCodeCompletions {

    /**
     * VS Code's `CompletionItemKind`, as the editor numbers it.
     *
     * The numbering is the workbench's own and does not match LSP's -- the host has already
     * converted it. Only the kinds with an obvious IntelliJ icon are mapped; the rest get none,
     * which reads better than a misleading one.
     */
    fun icon(kind: Int?): Icon? = when (kind) {
        0 -> AllIcons.Nodes.Method
        1 -> AllIcons.Nodes.Function
        2 -> AllIcons.Nodes.Method
        3 -> AllIcons.Nodes.Field
        4 -> AllIcons.Nodes.Variable
        5 -> AllIcons.Nodes.Class
        6 -> AllIcons.Nodes.Static
        7 -> AllIcons.Nodes.Interface
        8 -> AllIcons.Nodes.Module
        9 -> AllIcons.Nodes.Property
        14 -> AllIcons.Nodes.Enum
        15 -> AllIcons.Nodes.Enum
        18 -> AllIcons.Nodes.Tag
        27 -> AllIcons.Nodes.Folder
        else -> null
    }

    /**
     * Where the word ending at [offset] begins, by VS Code's default word definition: anything but
     * whitespace and the punctuation it lists.
     */
    fun wordStart(text: CharSequence, offset: Int): Int {
        var start = offset
        while (start > 0 && !text[start - 1].isWhitespace() && text[start - 1] !in SEPARATORS) start--
        return start
    }

    /** The punctuation in VS Code's `DEFAULT_WORD_REGEXP`, which no word contains. */
    private const val SEPARATORS = "`~!@#$%^&*()-=+[{]}\\|;:'\",.<>/?"
}
