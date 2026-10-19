package me.helvert.codebridge.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.UIUtil
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * That a save in the editor still reaches this plugin.
 *
 * This looks like a test of the platform, and that is the point. Saves carry more weight here than
 * any other document event: rust-analyzer runs `cargo check` on save and on nothing else, so every
 * error diagnostic it will ever report is downstream of this one callback arriving. If it stops
 * arriving, the visible symptom is not an exception or a warning -- it is an extension that behaves
 * as though the file were never saved, which reads exactly like a slow language server.
 *
 * `afterDocumentSaved` is declared as a default method, so an override compiles and reads correctly
 * whether or not anything ever calls it -- there is no warning for one that never runs. Reading
 * `FileDocumentManagerImpl` suggests the callback is published only on the subinterface's topic,
 * `FileDocumentManagerListenerBackgroundable`; moving the subscription there on that reading stopped
 * saves arriving entirely, and this test is what caught it. The bytecode is not the authority here.
 * This is.
 */
class DocumentSavedTest : EditorFixture() {

    @Test
    fun `saving a document announces it on the topic the plugin listens to`() = onEdt {
        // In the fixture's directory, which it removes itself once the editor is closed. Deleting the
        // file here instead raced the saves still in flight when the first announcement arrived, and
        // one of them would fail on a file that was no longer there.
        val path = Files.createTempFile(Path.of(fixture.tempDirPath), "codebridge-save", ".txt").also { it.writeText("before") }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)

        val announced = AtomicBoolean()
        ApplicationManager.getApplication().messageBus.connect(fixture.testRootDisposable)
            .subscribe(FileDocumentManagerListener.TOPIC,
                object : FileDocumentManagerListener {
                    override fun afterDocumentSaved(document: Document) { announced.set(true) }
                })

        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        val manager = FileDocumentManager.getInstance()

        // Pumped rather than awaited, and the edit is re-issued each round. This runs on the EDT and
        // part of saving is dispatched back onto it, so blocking here would prevent the very
        // announcement being waited for; and `saveDocument` does nothing at all for a document the
        // platform does not consider unsaved, which it intermittently does not in a light fixture.
        // Both of those made earlier versions of this test pass and fail on alternate runs.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
        var revision = 0
        while (!announced.get() && System.currentTimeMillis() < deadline) {
            @Suppress("UsePropertyAccessSyntax") // `text` has no setter
            WriteCommandAction.runWriteCommandAction(fixture.project) { document.setText("after ${revision++}") }
            manager.saveDocument(document)
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }

        assertTrue(
            announced.get(),
            "The platform no longer announces saves here; the extension host would never be told, " +
                "and every checkOnSave extension would go quiet without saying why",
        )
    }

    @Test
    fun `the plugin subscribes where the platform publishes`() {
        val source = Path.of("src", "main", "kotlin", "me", "helvert", "codebridge", "language", "HostedLanguageServers.kt")
        assertTrue(
            Files.readString(source.toAbsolutePath())
                .contains("subscribe(FileDocumentManagerListener.TOPIC"),
            "Saves must be taken from the topic that actually delivers them",
        )
    }
}
