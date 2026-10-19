package me.helvert.codebridge.ide

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeWorkspaceClassContributor
import me.helvert.codebridge.language.CodeBridgeWorkspaceSymbolsContributor
import me.helvert.codebridge.started
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Go to Class and Go to Symbol, from the query typed to the place an extension named.
 *
 * No host runs here, so the fixture answers `$provideWorkspaceSymbols` as an extension would.
 */
class WorkspaceSymbolsTest : EditorFixture() {

    @BeforeEach
    fun forgetPreviousHost() = onEdt {
        sent.clear()
        features.hostStopped()
    }

    @Test
    fun `the plugin registers both contributors`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeWorkspaceClassContributor"))
        assertTrue(descriptor.contains("me.helvert.codebridge.language.CodeBridgeWorkspaceSymbolsContributor"))
    }

    @Test
    fun `classes are the types, and symbols are everything`() = onEdt {
        val source = file("source.txt", "struct Point\nfn point_at()\n")
        answer(
            resolves = false, symbols = listOf(
                symbol("Point", 22, source, 1, 8),
                symbol("point_at", 11, source, 2, 4),
            )
        )

        assertEquals(listOf("Point"), names(CodeBridgeWorkspaceClassContributor(), "po"))
        assertEquals(listOf("Point", "point_at"), names(CodeBridgeWorkspaceSymbolsContributor(), "po"))
    }

    @Test
    fun `one query is asked once, however many times the popup asks it`() = onEdt {
        val source = file("source.txt", "struct Point\n")
        answer(resolves = false, symbols = listOf(symbol("Point", 22, source, 1, 8)))

        names(CodeBridgeWorkspaceClassContributor(), "po")
        names(CodeBridgeWorkspaceSymbolsContributor(), "po")
        elements(CodeBridgeWorkspaceClassContributor(), "Point", "po")
        names(CodeBridgeWorkspaceClassContributor(), "poi")

        assertEquals(
            listOf("""[5,"po"]""", "[5,7]", """[5,"poi"]""", "[5,7]"),
            sent.toList(),
            "Asked, then released at once"
        )
    }

    @Test
    fun `symbols outside the project are left out unless libraries are searched`() = onEdt {
        // The fixture's files are outside its project, which is what a dependency's sources are.
        val source = file("source.txt", "struct Point\n")
        answer(resolves = false, symbols = listOf(symbol("Point", 22, source, 1, 8)))

        assertEquals(1, elements(CodeBridgeWorkspaceClassContributor(), "Point", "po", libraries = true).size)
        assertEquals(0, elements(CodeBridgeWorkspaceClassContributor(), "Point", "po", libraries = false).size)
    }

    @Test
    fun `choosing a symbol opens its position`() = onEdt {
        val source = file("source.txt", "first line\nstruct Point\n")
        answer(resolves = false, symbols = listOf(symbol("Point", 22, source, 2, 8)))

        val item = elements(CodeBridgeWorkspaceClassContributor(), "Point", "po").single()
        assertEquals("source.txt", item.presentation?.locationString)
        item.navigate(true)

        val editor = FileEditorManager.getInstance(fixture.project).selectedTextEditor
        assertEquals(source, FileEditorManager.getInstance(fixture.project).selectedFiles.single())
        assertEquals(18, editor?.caretModel?.offset)
    }

    @Test
    fun `a provider that resolves keeps its list until the next query, and resolves from it`() = onEdt {
        val source = file("source.txt", "first line\nstruct Point\n")
        answer(
            resolves = true,
            symbols = listOf(symbol("Point", 22, source, 1, 1)),
            resolved = symbol("Point", 22, source, 2, 8)
        )

        val offered = features.workspaceSymbols("po").single()
        assertEquals(listOf("""[5,"po"]"""), sent.toList(), "Held, not released")
        assertEquals(2, PlatformTestUtil.waitForFuture(started { features.resolveWorkspaceSymbol(offered) }, 5_000).range.startLineNumber)

        features.workspaceSymbols("poi")
        assertTrue(sent.contains("[5,7]"), "Released once superseded: $sent")
    }

    private fun names(contributor: com.intellij.navigation.ChooseByNameContributorEx2, pattern: String): List<String> {
        val names = CommonProcessors.CollectProcessor<String>()
        contributor.processNames(names, parameters(pattern, libraries = true))
        return names.results.toList()
    }

    private fun elements(
        contributor: com.intellij.navigation.ChooseByNameContributorEx2,
        name: String,
        pattern: String,
        libraries: Boolean = true,
    ): List<NavigationItem> {
        val items = CommonProcessors.CollectProcessor<NavigationItem>()
        contributor.processElementsWithName(name, items, parameters(pattern, libraries))
        return items.results.toList()
    }

    private fun parameters(pattern: String, libraries: Boolean) =
        FindSymbolParameters.wrap(pattern, fixture.project, libraries)

    /** Registers a workspace symbol provider answering every query with [symbols], under list 7. */
    private fun answer(resolves: Boolean, symbols: List<String>, resolved: String? = null) {
        features.handle("MainThreadLanguageFeatures", $$"$registerNavigateTypeSupport", "[5,$resolves]")
        features.callHostForTest { _, method, args, _ ->
            sent.add(args.takeUnless { method == $$"$resolveWorkspaceSymbol" } ?: "resolve")
            
                when (method) {
                    $$"$provideWorkspaceSymbols" -> """{"cacheId":7,"symbols":[${symbols.joinToString(",")}]}"""
                    $$"$resolveWorkspaceSymbol" -> resolved ?: ""
                    else -> ""
                }
            
        }
    }

    private fun symbol(name: String, kind: Int, file: VirtualFile, line: Int, column: Int) =
        """{"name":"$name","kind":$kind,"location":{"uri":${UriComponents.of(file.toNioPath()).toJson()},""" +
                """"range":{"startLineNumber":$line,"startColumn":$column,"endLineNumber":$line,"endColumn":$column}},"cacheId":[7,0]}"""

    @Suppress("SameParameterValue")
    private fun file(name: String, text: String): VirtualFile {
        val path = directory.resolve(name).also { it.writeText(text) }
        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    private val sent = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-symbols") }
}
