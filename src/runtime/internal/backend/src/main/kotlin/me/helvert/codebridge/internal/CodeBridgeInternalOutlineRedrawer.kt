// Internal API is this module's purpose; each use is listed in docs/platform-limitations.md.
@file:Suppress("UnstableApiUsage")

package me.helvert.codebridge.internal

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.stickyLines.StickyLinesCollector
import com.intellij.openapi.project.Project
import com.intellij.xml.breadcrumbs.reinitBreadcrumbsInAllEditors
import me.helvert.codebridge.language.CodeBridgeOutlineRedrawer

/**
 * Has the sticky lines pass and the breadcrumbs bar draw an outline they did not ask for, through
 * the only ways the platform has: marking the file as needing the pass, and deciding again which
 * editors have a bar. Both internal, so a platform that renames them only loses the redraw.
 */
internal class CodeBridgeInternalOutlineRedrawer : CodeBridgeOutlineRedrawer {
    override fun outlinePushed(project: Project, document: Document) {
        try {
            StickyLinesCollector(project, document).forceCollectPass()
        } catch (_: LinkageError) {
        }
    }

    override fun outlineAppearedOrWent(project: Project) {
        try {
            reinitBreadcrumbsInAllEditors(project, true)
        } catch (_: LinkageError) {
        }
    }
}
