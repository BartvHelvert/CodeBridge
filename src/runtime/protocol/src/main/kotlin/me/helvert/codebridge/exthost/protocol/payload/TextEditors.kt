package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * `MainThreadTextEditors`: what an extension does to an editor rather than to a document.
 *
 * Read off the 1.135 bundle's `ExtHostTextEditor`. The editor ids are the ones the host was given
 * when the document was mirrored, so an extension can only name editors it has been told about.
 * Language clients reach this more than it looks: `window.showTextDocument` followed by
 * `editor.edit` is how rust-analyzer applies every assist that is not a snippet.
 */
object TextEditors {

    const val SERVICE: String = "MainThreadTextEditors"

    /** `$tryShowTextDocument(resource, options)`, answered with the editor id, or nothing if none. */
    const val SHOW: String = $$"$tryShowTextDocument"
    /** `$tryApplyEdits(id, documentVersionId, edits, options)`, answered with whether they applied. */
    const val APPLY_EDITS: String = $$"$tryApplyEdits"
    /** `$tryInsertSnippet(id, documentVersionId, template, ranges, options)`, answered likewise. */
    const val INSERT_SNIPPET: String = $$"$tryInsertSnippet"
    const val SET_SELECTIONS: String = $$"$trySetSelections"
    const val REVEAL_RANGE: String = $$"$tryRevealRange"

    /** Where to show a document, and whether to take focus doing it. */
    data class Show(val resource: UriComponents, val selection: Range?, val preserveFocus: Boolean)

    /** Edits against one editor's document, and the version they were computed against. */
    data class Edits(val editorId: String, val versionId: Int?, val edits: List<TextEdit>)

    fun show(argsJson: String): Show {
        val args = Args.of(SHOW, argsJson)
        val options = args.objOrNull(1)
        return Show(
            args.uri(0),
            Range.from(options?.get("selection")),
            (options?.get("preserveFocus") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    fun edits(argsJson: String): Edits {
        val args = Args.of(APPLY_EDITS, argsJson)
        return Edits(args.str(0), args.intOrNull(1), args.array(2).mapNotNull(TextEdit::from))
    }

    /**
     * A snippet inserted at each of its ranges, as edits: VS Code's multi-cursor insertion, one stop
     * walked at every range at once.
     *
     * The host always names the ranges, falling back to the editor's selections itself. Of the
     * options only `keepWhitespace` is read, which the host defaults to false; the undo stops have
     * no counterpart here.
     */
    fun snippet(argsJson: String): Edits {
        val args = Args.of(INSERT_SNIPPET, argsJson)
        val template = args.str(2)
        val ranges = args.arrayOrNull(3).orEmpty().mapNotNull(Range::from)
        val keepWhitespace = (args.objOrNull(4)?.get("keepWhitespace") as? JsonPrimitive)?.booleanOrNull == true
        return Edits(args.str(0), args.intOrNull(1), ranges.map { TextEdit(it, template, true, keepWhitespace) })
    }

    /** The editor id every `$try*` method on one editor names first. */
    fun editorId(method: String, argsJson: String): String = Args.of(method, argsJson).str(0)

    /**
     * `$trySetSelections(id, selections)`: the first selection, as a range from anchor to caret.
     *
     * VS Code's `ISelection` names its ends by role rather than by order, so the caret -- the
     * `position` end -- may come first in the document.
     */
    fun selection(argsJson: String): Pair<Position, Position>? {
        val first = Args.of(SET_SELECTIONS, argsJson).arrayOrNull(1)?.firstOrNull() as? JsonObject ?: return null
        fun int(name: String) = (first[name] as? JsonPrimitive)?.intOrNull
        val anchor = Position(int("selectionStartLineNumber") ?: return null, int("selectionStartColumn") ?: return null)
        val caret = Position(int("positionLineNumber") ?: return null, int("positionColumn") ?: return null)
        return anchor to caret
    }

    /** `$tryRevealRange(id, range, revealType)`. */
    fun revealed(argsJson: String): Range? = Range.from(Args.of(REVEAL_RANGE, argsJson).element(1))
}
