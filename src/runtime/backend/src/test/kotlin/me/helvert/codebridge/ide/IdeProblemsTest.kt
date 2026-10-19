package me.helvert.codebridge.ide

import com.intellij.lang.LanguageExtensionPoint
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import me.helvert.codebridge.exthost.ExtensionHostDocuments
import me.helvert.codebridge.exthost.protocol.payload.Diagnostics
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeDiagnosticsAnnotator
import me.helvert.codebridge.language.CodeBridgeIdeProblems
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * IntelliJ's own problems reaching the host, where `languages.getDiagnostics` reads them -- what
 * Error Lens needs to show anything in a file no extension analyses.
 */
class IdeProblemsTest : EditorFixture() {

    private val sent = CopyOnWriteArrayList<String>()

    @Test
    fun `a problem the IDE found is sent to the host, and one an extension pushed is not`() = onEdt {
        registerAnnotator()
        val features = HostedLanguageFeatures.getInstance(fixture.project)
        try {
            val path = Files.createTempDirectory("codebridge-ide-problems").toRealPath().resolve("broken.json")
            path.writeText("{\"ok\": 1, \"missing\": }\n")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            fixture.openFileInEditor(file)
            features.mirrorForTest(ExtensionHostDocuments { _, _, _ -> "" })
            features.callHostForTest { service, method, args, _ ->
                if (service == Diagnostics.EXT_HOST && method == Diagnostics.ACCEPT_MARKERS_CHANGE) sent += args
                "null"
            }
            features.documentOpened(file, "json", HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false))
            val uri = UriComponents.of(path).toJson()
            features.handle(
                "MainThreadDiagnostics", Diagnostics.CHANGE_MANY,
                """["probe",[[$uri,[{"startLineNumber":1,"startColumn":2,"endLineNumber":1,"endColumn":6,"severity":8,"message":"$PUSHED"}]]]]"""
            )
            features.awaitRepaintsForTest()
            UIUtil.dispatchAllInvocationEvents()
            val drawn = fixture.doHighlighting()
            assertTrue(drawn.any { it.description == PUSHED }, "The premise is the pushed problem being drawn: $drawn")

            CodeBridgeIdeProblems.getInstance(fixture.project).refresh(file)
            val markers = awaitSent().second
            assertTrue(markers.none { it.message == PUSHED }, "The extension's own problem came back to it: $markers")
            val error = markers.firstOrNull { it.severity == Marker.ERROR }
                ?: fail("No error for the JSON parse failure: $markers; drawn: ${drawn.map { "${it.severity}/${it.description}" }}")
            assertEquals(1, error.range.startLineNumber)
            assertTrue(error.source!!.isNotBlank())

            // Closing withdraws them, with an empty list.
            sent.clear()
            features.documentClosed(file)
            assertEquals(emptyList(), awaitSent().second)
        } finally {
            features.hostStopped()
        }
    }

    private fun registerAnnotator() {
        val descriptor = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge"),
            CodeBridgeDiagnosticsAnnotator::class.java.classLoader
        )
        ANNOTATORS.point.registerExtension(
            LanguageExtensionPoint("", CodeBridgeDiagnosticsAnnotator::class.java.name, descriptor),
            fixture.testRootDisposable,
        )
    }

    /** The last `$acceptMarkersChange`, read back: its one resource and that resource's markers. */
    private fun awaitSent(): Pair<String, List<Marker>> {
        val deadline = System.currentTimeMillis() + 10_000
        while (sent.isEmpty()) {
            if (System.currentTimeMillis() > deadline) fail("Nothing was sent to the host")
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        val entry = Json.parseToJsonElement(sent.last()).jsonArray.single().jsonArray.single().jsonArray
        return entry[0].toString() to entry[1].jsonArray.map(Marker::from)
    }

    private companion object {
        val ANNOTATORS = ExtensionPointName<LanguageExtensionPoint<Annotator>>("com.intellij.annotator")

        const val PUSHED = "Pushed by an extension"
    }
}
