package me.helvert.codebridge.textmate

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl
import me.helvert.codebridge.language.CodeBridgeFileLanguageProvider

/**
 * Registers this module's [CodeBridgeFileLanguageProvider], through which extension changes reload
 * the TextMate bundles. The light fixture doesn't load CodeBridge's descriptors, so the point itself,
 * declared in `CodeBridge.backend.xml`, has to be stood up too.
 */
internal fun registerFileLanguage(disposable: Disposable) {
    val area = ApplicationManager.getApplication().extensionArea as ExtensionsAreaImpl
    if (!area.hasExtensionPoint(CodeBridgeFileLanguageProvider.EP_NAME)) {
        area.registerExtensionPoint(
            CodeBridgeFileLanguageProvider.EP_NAME, CodeBridgeFileLanguageProvider::class.java.name,
            ExtensionPoint.Kind.INTERFACE, disposable,
        )
    }
    CodeBridgeFileLanguageProvider.EP_NAME.point.registerExtension(CodeBridgeTextMateFileLanguage(), disposable)
}
