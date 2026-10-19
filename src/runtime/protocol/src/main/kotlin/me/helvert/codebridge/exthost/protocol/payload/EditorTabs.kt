package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The editor tabs the workbench is showing, as `ExtHostEditorTabs` models them.
 *
 * This looks like decoration and is not. `window.tabGroups` is what several clients use to decide
 * which documents are *worth working on*, and the one that matters here is the language client's
 * pull-diagnostics feature: it builds its set of open resources by walking `tabGroups.all`, and asks
 * `isVisible(document)` before requesting `textDocument/diagnostic` for it.
 *
 * With no tab model, that set is empty, every document is invisible, and the feature answers each
 * `workspace/diagnostic/refresh` the server sends and then pulls nothing. A server whose live
 * analysis is pull-based -- rust-analyzer's is -- then appears to produce no diagnostics at all
 * while typing, and the only ones that ever arrive are the `cargo check` results it pushes on save.
 * Reporting editors is not enough on its own: the pull feature reads tabs, not visible editors.
 *
 * The model is sent whole rather than as a delta. VS Code sends the complete set on every change
 * too, and a set this small -- one group, one tab per mirrored document -- is not worth diffing.
 */
object EditorTabs {

    const val TABS: String = "ExtHostEditorTabs"
    const val ACCEPT_MODEL: String = $$"$acceptEditorTabModel"

    /** `TabInputKind.TextInput`, which the runtime revives as a `TabInputText`. */
    private const val TEXT_INPUT = 1

    /** One group, because IntelliJ's splits are not mirrored and one is enough to be visible in. */
    private const val GROUP_ID = 1

    /**
     * The whole tab model: one group holding one tab per open document.
     *
     * [active] names the document whose tab is marked active, which is how `activeTab` and
     * `activeTextEditor`-shaped questions are answered. A null or unknown [active] leaves every tab
     * inactive, which is what an editor-less workspace should look like rather than a wrong guess.
     */
    fun model(documents: List<Tab>, active: String?): String =
    // One argument, which is the list of groups -- so the argument list wraps it. Sending the
    // groups as the argument list itself makes the host receive a single group where it expects
        // an array, and `$acceptEditorTabModel` fails with "t.map is not a function".
        JsonArray(listOf(JsonArray(listOf(buildJsonObject {
            put("groupId", GROUP_ID)
            put("isActive", true)
            // EditorGroupColumn 0, revived as ViewColumn.One -- the same column the editors report.
            put("viewColumn", 0)
            put("tabs", JsonArray(documents.map { tab(it, it.uri == active) }))
        })))).toString()

    /** One open document, as the tab showing it. */
    data class Tab(val id: String, val uri: String, val label: String, val dirty: Boolean)

    private fun tab(value: Tab, active: Boolean): JsonObject = buildJsonObject {
        put("id", value.id)
        put("label", value.label)
        put("input", buildJsonObject {
            put("kind", TEXT_INPUT)
            put("uri", UriComponents.of(value.uri).toJson())
        })
        put("isActive", active)
        // Pinned and not a preview: an IntelliJ tab does not close itself when the next file opens,
        // and an extension that treats a preview tab as disposable would be wrong about this one.
        put("isPinned", true)
        put("isPreview", false)
        put("isDirty", value.dirty)
    }

    /** The label a tab shows, which VS Code takes from the file name. */
    fun labelOf(uri: String): String =
        uri.trimEnd('/').substringAfterLast('/').substringAfterLast('\\').ifEmpty { uri }

    /** Ids only have to be stable and distinct; the runtime matches tabs across updates by them. */
    fun idOf(editorId: String): String = "$editorId-tab"
}
