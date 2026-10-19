package me.helvert.codebridge.ide

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Decorations
import me.helvert.codebridge.workbench.HostedDecorations
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * File decorations, from a provider's answers to what the Project view decorator is handed: asking
 * in a batch about files drawn before anything is known, a folder taking a propagated colour, and
 * `$onDidChange` asking again.
 */
class DecorationsTest : EditorFixture() {

    private val directory by lazy { Files.createTempDirectory("codebridge-decorations") }
    private val asked = CopyOnWriteArrayList<String>()

    @Test
    fun `files are asked about in a batch, and a folder takes a propagated colour`() = onEdt {
        val decorations = HostedDecorations(fixture.project)
        val src = directory.resolve("src").createDirectories()
        val a = file(src.resolve("a.rs").also { it.writeText("") })
        val b = file(src.resolve("b.rs").also { it.writeText("") })
        val folder = file(src)
        decorations.hostStarted { _, method, args ->
            assertEquals(Decorations.PROVIDE, method)
            asked += args
            // Only a.rs is decorated, by whichever index the batch gave it.
            val requests = (Json.parseToJsonElement(args) as JsonArray)[1] as JsonArray
            val index = requests.indexOfFirst { it.toString().contains("a.rs") }
            if (index < 0) "{}" else """{"$index":[true,"Modified","M",{"id":"list.errorForeground"}]}"""
        }
        assertNull(decorations.decoration(a), "No provider, nothing to ask")
        decorations.handle(MainThreadCall(Decorations.SERVICE, Decorations.REGISTER, """[1,"test.ext"]"""))

        assertNull(decorations.decoration(a), "Not known yet")
        assertNull(decorations.decoration(b))
        settle(decorations)
        assertEquals(1, asked.size, "One batch for both")

        val shown = assertNotNull(decorations.decoration(a))
        assertEquals("M", shown.badge)
        assertEquals("Modified", shown.tooltip)
        assertNotNull(shown.color)
        assertNull(decorations.decoration(b))
        assertEquals("•", decorations.decoration(folder)?.badge)
        assertEquals(1, asked.size, "Known now, so not asked again")

        decorations.handle(
            MainThreadCall(
                Decorations.SERVICE,
                Decorations.CHANGED,
                """[1,[{"scheme":"file","path":"${a.toNioPath().toUri().path}"}]]"""
            )
        )
        assertNull(decorations.decoration(a), "Forgotten, so asked again")
        settle(decorations)
        assertEquals(2, asked.size)

        decorations.handle(MainThreadCall(Decorations.SERVICE, Decorations.UNREGISTER, "[1]"))
        assertNull(decorations.decoration(a))
    }

    private fun file(path: java.nio.file.Path): VirtualFile =
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))

    private fun settle(decorations: HostedDecorations) {
        kotlin.test.assertTrue(decorations.awaitForTest(), "The decoration request never settled")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
}
