package me.helvert.codebridge.language

import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.awt.datatransfer.DataFlavor
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * The values VS Code gives a snippet's variables ([Snippets.KNOWN]) where it goes in: at [offset] in
 * [editor], showing [file].
 *
 * Read as the snippet is offered, which is as it is inserted for every value but the time. Names and
 * months are English, as VS Code's are unless its display language is changed.
 */
internal class SnippetVariables(
    private val project: Project,
    private val editor: Editor,
    private val file: VirtualFile,
    private val psiFile: PsiFile?,
    private val offset: Int,
) : Snippets.Variables {

    private val now by lazy { ZonedDateTime.now() }

    override fun value(name: String): String? = when (name) {
        "TM_SELECTED_TEXT", "SELECTION" -> editor.selectionModel.selectedText
        "TM_CURRENT_LINE" -> line()
        "TM_CURRENT_WORD" -> word()
        "TM_LINE_INDEX" -> lineNumber().toString()
        "TM_LINE_NUMBER" -> (lineNumber() + 1).toString()
        "TM_FILENAME" -> file.name
        "TM_FILENAME_BASE" -> file.name.substringBeforeLast('.').ifEmpty { file.name }
        "TM_DIRECTORY" -> file.parent?.path?.let(FileUtil::toSystemDependentName)
        "TM_FILEPATH" -> FileUtil.toSystemDependentName(file.path)
        "RELATIVE_FILEPATH" -> project.basePath
            ?.let { FileUtil.getRelativePath(it, file.path, '/') }
            ?.let(FileUtil::toSystemDependentName)
            ?: FileUtil.toSystemDependentName(file.path)
        "CLIPBOARD" -> CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        "WORKSPACE_NAME" -> project.name
        "WORKSPACE_FOLDER" -> project.basePath?.let(FileUtil::toSystemDependentName)
        "CURSOR_INDEX" -> editor.caretModel.allCarets.indexOf(editor.caretModel.currentCaret).coerceAtLeast(0).toString()
        "CURSOR_NUMBER" -> (editor.caretModel.allCarets.indexOf(editor.caretModel.currentCaret).coerceAtLeast(0) + 1).toString()
        "CURRENT_YEAR" -> now.year.toString()
        "CURRENT_YEAR_SHORT" -> now.year.toString().takeLast(2)
        "CURRENT_MONTH" -> two(now.monthValue)
        "CURRENT_MONTH_NAME" -> now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        "CURRENT_MONTH_NAME_SHORT" -> now.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        "CURRENT_DATE" -> two(now.dayOfMonth)
        "CURRENT_DAY_NAME" -> now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        "CURRENT_DAY_NAME_SHORT" -> now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        "CURRENT_HOUR" -> two(now.hour)
        "CURRENT_MINUTE" -> two(now.minute)
        "CURRENT_SECOND" -> two(now.second)
        "CURRENT_SECONDS_UNIX" -> now.toEpochSecond().toString()
        "CURRENT_TIMEZONE_OFFSET" -> now.offset.totalSeconds.let { seconds ->
            val minutes = Math.abs(seconds) / 60
            (if (seconds < 0) "-" else "+") + two(minutes / 60) + ":" + two(minutes % 60)
        }
        "RANDOM" -> (0 until 6).joinToString("") { Random.nextInt(10).toString() }
        "RANDOM_HEX" -> (0 until 6).joinToString("") { Random.nextInt(16).toString(16) }
        "UUID" -> UUID.randomUUID().toString()
        "BLOCK_COMMENT_START" -> commenter()?.blockCommentPrefix
        "BLOCK_COMMENT_END" -> commenter()?.blockCommentSuffix
        "LINE_COMMENT" -> commenter()?.lineCommentPrefix?.trimEnd()
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private fun lineNumber(): Int = editor.document.getLineNumber(offset.coerceIn(0, editor.document.textLength))

    private fun line(): String {
        val document = editor.document
        val line = lineNumber()
        return document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString()
    }

    /** The word at the caret, by VS Code's default word definition. */
    private fun word(): String {
        val text = editor.document.charsSequence
        val start = CodeBridgeCompletionContributor.wordStart(text, offset)
        var end = offset
        while (end < text.length && CodeBridgeCompletionContributor.wordStart(text, end + 1) <= start) end++
        return text.subSequence(start, end).toString()
    }

    private fun commenter() = psiFile?.language?.let(LanguageCommenters.INSTANCE::forLanguage)

    private fun two(value: Int) = value.toString().padStart(2, '0')
}
