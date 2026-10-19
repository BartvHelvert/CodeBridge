package me.helvert.codebridge.textmate

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.helvert.codebridge.extensions.ExtensionInstallationListener
import org.jetbrains.plugins.textmate.TextMateService

/** One application listener, including when no project has requested a language server yet. */
internal class CodeBridgeTextMateInstallationListener : ExtensionInstallationListener {
    override fun extensionsChanged() {
        ApplicationManager.getApplication().getService(CodeBridgeTextMateReloads::class.java).requestReload()
    }
}

/**
 * Serializes reloads off the publisher thread and coalesces bursts of installation events.
 *
 * The reload itself happens inside a file-type change, in one write action. Reloading changes which
 * language a file TextMate reads has -- a plain text file is `textmate` while a grammar claims it,
 * plain text again once none does -- and a reload made beside the change, with the event fired
 * after it, let anything that looked in between find the file's language changed without notice.
 * The PSI does on entering dumb mode, and fails loudly: "View provider resurrection failed:
 * different base languages", which in a test left dumb mode never ending.
 */
@Service(Service.Level.APP)
internal class CodeBridgeTextMateReloads(scope: CoroutineScope) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            requests.consumeEach {
                try {
                    val textmate = TextMateService.getInstance()
                    // Reading the mapping waits for initial bundle loading off the editor thread.
                    // Unlike ensureInitialized(), this remains public in newer platform builds.
                    textmate.fileNameMatcherToScopeNameMapping
                    // Waited for, so reloads stay one after another.
                    withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                        WriteAction.run<RuntimeException> {
                            // Always an event, also when only grammar contents changed: TextMate fires
                            // one of its own only when the filename-to-scope mapping does, and editors
                            // need one to rebuild their highlighters.
                            FileTypeManagerEx.getInstanceEx().makeFileTypesChange("CodeBridge TextMate grammars changed") {
                                textmate.reloadEnabledBundles()
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Logger.getInstance(CodeBridgeTextMateReloads::class.java)
                        .warn("Could not reload CodeBridge TextMate bundles", failure)
                }
            }
        }
    }

    fun requestReload() {
        requests.trySend(Unit)
    }
}
