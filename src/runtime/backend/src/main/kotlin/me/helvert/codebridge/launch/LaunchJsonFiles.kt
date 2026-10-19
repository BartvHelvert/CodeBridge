package me.helvert.codebridge.launch

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.debug.DebugLaunch
import me.helvert.codebridge.workspace.Jsonc
import me.helvert.codebridge.launch.LaunchJsonEdit.Section
import java.nio.file.Files
import java.nio.file.Path

/**
 * The files `launch.json` entries are written in, as the IDE holds them: a file loaded in an editor
 * is read and written through its document, so the editor shows a change at once and can undo it.
 */
internal object LaunchJsonFiles {

    /** The file a folder's entries are in, and the keys its launch section is under; see [DebugLaunch]. */
    class Place(val file: Path, val prefix: List<String>)

    fun place(workspace: Path, folder: String?): Place {
        val layout = DebugLaunch.layout(workspace)
        return when {
            folder != null -> {
                val found = layout.folders.firstOrNull { it.name == folder }
                    ?: throw IllegalArgumentException("There is no workspace folder named '$folder'")
                Place(layout.folderFile(found, "launch.json"), emptyList())
            }
            layout.file != null -> Place(layout.file!!, listOf("launch"))
            else -> Place(workspace.resolve(".vscode/launch.json"), emptyList())
        }
    }

    /** The file's text, as its editor has it when one has it loaded; null when there is no such file. */
    fun text(place: Place): String? =
        document(place)?.immutableCharSequence?.toString() ?: place.file.takeIf(Files::isRegularFile)?.let(Files::readString)

    /** The entry named [name] in [section] of [folder]'s file, as written now; null when there is not exactly one. */
    fun entry(workspace: Path, folder: String?, section: Section, name: String) = runCatching {
        val place = place(workspace, folder)
        text(place)?.let { LaunchJsonEdit.entry(it, place.prefix, section, name) }
    }.getOrNull()

    /** The `id` of each of the `inputs` [folder]'s entries can use, as `${input:id}`, as written now. */
    fun inputs(workspace: Path, folder: String?): List<String> = runCatching {
        val place = place(workspace, folder)
        var node: JsonElement = Json.parseToJsonElement(Jsonc.stripToJson(text(place) ?: return emptyList()))
        for (key in place.prefix + "inputs") node = (node as? JsonObject)?.get(key) ?: return emptyList()
        (node as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
    }.getOrDefault(emptyList())

    /**
     * Changes the entry named [name] in [section] of [folder]'s file by [changes], as one command that
     * can be undone, and saves it. Editor thread.
     */
    fun change(project: Project, workspace: Path, folder: String?, section: Section, name: String, changes: EntryChanges) {
        ThreadingAssertions.assertEventDispatchThread()
        val place = place(workspace, folder)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(place.file)
            ?: throw IllegalArgumentException("${place.file} does not exist")
        val document = FileDocumentManager.getInstance().getDocument(virtual)
            ?: throw IllegalArgumentException("${place.file} cannot be edited")
        val before = document.immutableCharSequence.toString()
        val after = LaunchJsonEdit.apply(before, place.prefix, section, name, changes, folder)
        if (after == before) return
        // Only the span that changed, so the caret and folding elsewhere stay put.
        val start = before.commonPrefixWith(after).length
        val end = before.length - before.substring(start).commonSuffixWith(after.substring(start)).length
        WriteCommandAction.runWriteCommandAction(project, "Edit Launch Configuration", null, {
            document.replaceString(start, end, after.substring(start, after.length - (before.length - end)))
        })
        FileDocumentManager.getInstance().saveDocument(document)
    }

    /** Opens [folder]'s file at the entry named [name] in [section], or at its top when it cannot be found. */
    fun open(project: Project, workspace: Path, folder: String?, section: Section, name: String) {
        val place = runCatching { place(workspace, folder) }.getOrNull() ?: return
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(place.file) ?: return
        val text = text(place).orEmpty()
        // The entry's name as it is written, in its section: near enough to find it by.
        val from = text.indexOf("\"${section.key}\"").coerceAtLeast(0)
        val offset = Regex("\"name\"\\s*:\\s*${Regex.escape(JsonPrimitive(name).toString())}")
            .find(text, from)?.range?.first ?: from
        OpenFileDescriptor(project, virtual, offset).navigate(true)
    }

    private fun document(place: Place): Document? {
        val virtual = LocalFileSystem.getInstance().findFileByNioFile(place.file) ?: return null
        return FileDocumentManager.getInstance().getCachedDocument(virtual)
    }
}
