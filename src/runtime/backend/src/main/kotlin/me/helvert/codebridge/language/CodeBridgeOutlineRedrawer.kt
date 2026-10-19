package me.helvert.codebridge.language

import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Whatever draws a file's outline and only redraws it on the platform's own schedule: the sticky
 * lines pass and the breadcrumbs bar, which [HostedLanguageFeatures] cannot otherwise make draw an
 * outline nothing in the IDE asked for.
 *
 * A point rather than a call because both are reached through internal API, kept in
 * `CodeBridge.internal.backend` so a build without that module ships none of it. Without an implementation
 * the outline is still drawn, only from the next edit or reopening on.
 */
interface CodeBridgeOutlineRedrawer {
    /**
     * An outline of [document] arrived that nothing asked for. In a read action, off the EDT, before
     * the file's highlighting is restarted.
     */
    fun outlinePushed(project: Project, document: Document) = Unit

    /** A file in [project] got its first outline, or lost its last. On the EDT. */
    fun outlineAppearedOrWent(project: Project) = Unit

    companion object {
        val EP_NAME: ExtensionPointName<CodeBridgeOutlineRedrawer> =
            ExtensionPointName("me.helvert.codebridge.outlineRedrawer")
    }
}
