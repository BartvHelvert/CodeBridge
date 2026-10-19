package me.helvert.codebridge.testing.ui


import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Where a test is, from the `locationHint` it was reported with:
 * `codebridgetest://<test id>/<line>/<column>/<file URI>`, the id URL-encoded and the position 1-based.
 *
 * The place is spelled out rather than looked up, so a finished run still navigates after the host
 * that reported it has gone. The id is there for Rerun Failed Tests ([testId]).
 */
object CodeBridgeTestLocator : SMTestLocator {
    const val PROTOCOL: String = "codebridgetest"

    fun hint(extId: String, uri: String, line: Int?, column: Int?): String =
        "$PROTOCOL://${URLEncoder.encode(extId, StandardCharsets.UTF_8)}/${line ?: 0}/${column ?: 0}/$uri"

    /** The test a location URL names, if it is one of these. */
    fun testId(locationUrl: String?): String? {
        val path = locationUrl?.removePrefix("$PROTOCOL://")?.takeIf { it != locationUrl } ?: return null
        return URLDecoder.decode(path.substringBefore('/'), StandardCharsets.UTF_8)
    }

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope
    ): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val parts = path.split('/', limit = 4)
        if (parts.size < 4) return emptyList()
        val line = parts[1].toIntOrNull() ?: 0
        val column = parts[2].toIntOrNull() ?: 0
        // The URI as the extension spelled it -- `file:///c:/x`, escapes and all -- read back to the file
        // it names rather than rewritten into a VFS URL, which on Linux lost the path's leading slash.
        val local = runCatching { UriComponents.of(parts[3]).toPath() }.getOrNull() ?: return emptyList()
        val file = LocalFileSystem.getInstance().findFileByNioFile(local) ?: return emptyList()
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(file)
        val offset = if (document != null && line in 1..document.lineCount)
            (document.getLineStartOffset(line - 1) + (column - 1).coerceAtLeast(0)).coerceAtMost(
                document.getLineEndOffset(
                    line - 1
                )
            )
        else 0
        val element = psi.findElementAt(offset) ?: psi
        return listOf(At(project, element, file, line, column))
    }

    /**
     * The element at the test, opened at the test's own line and column: the element may be the
     * file's only one, as it is in a plain-text or TextMate file, and would open at its start.
     */
    private class At(
        project: Project,
        element: PsiElement,
        private val file: com.intellij.openapi.vfs.VirtualFile,
        private val line: Int,
        private val column: Int
    ) :
        PsiLocation<PsiElement>(project, element) {
        override fun getOpenFileDescriptor(): OpenFileDescriptor =
            if (line > 0) OpenFileDescriptor(project, file, line - 1, (column - 1).coerceAtLeast(0))
            else OpenFileDescriptor(project, file)
    }
}
