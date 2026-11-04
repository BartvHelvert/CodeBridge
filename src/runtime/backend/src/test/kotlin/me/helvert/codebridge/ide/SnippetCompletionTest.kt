package me.helvert.codebridge.ide

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.language.CodeBridgeSnippetCompletion
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.HostedLanguageServers
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Snippets an extension contributes, offered in completion as VS Code offers them.
 *
 * The extension here is nothing but data -- no `main`, no host -- as most snippet packs are. One
 * file is for its own language, the other global, scoped by each snippet's `scope`.
 */
class SnippetCompletionTest : EditorFixture() {

    private var autocomplete = false

    @BeforeEach
    fun install() = onEdt {
        // A light fixture loads no plugin.xml, so the contributor is registered by hand.
        val descriptor = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge"),
            CodeBridgeSnippetCompletion::class.java.classLoader,
        )
        CONTRIBUTORS.point.registerExtension(
            CompletionContributorEP("any", CodeBridgeSnippetCompletion::class.java.name, descriptor),
            fixture.testRootDisposable,
        )
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        TemplateManagerImpl.setTemplateTesting(fixture.testRootDisposable)
        HostedLanguageFeatures.getInstance(fixture.project).hostStopped()
        installVsix()
    }

    @AfterEach
    fun uninstall() = onEdt {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        servers.settingsForTest = null
        ExtensionInstallationService.getInstance().uninstall(ID)
    }

    @Test
    fun `the plugin registers the contributor for every language`() {
        val descriptor = Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(
            descriptor.contains(
                """<completion.contributor language="any" implementationClass="me.helvert.codebridge.language.CodeBridgeSnippetCompletion"/>"""
            ),
            "Nothing in a light fixture would notice this being deleted",
        )
    }

    @Test
    fun `a snippet is offered by its prefix in a file of its language`() = onEdt {
        open("fo", "main.snip.txt")

        fixture.completeBasic()

        assertEquals(listOf("forr"), fixture.lookupElementStrings)
    }

    @Test
    fun `a language's snippet is not offered in a file of another language`() = onEdt {
        open("fo", "notes.txt")

        fixture.completeBasic()

        assertEquals(emptyList(), fixture.lookupElementStrings.orEmpty())
    }

    @Test
    fun `a global snippet goes where its scope says, and one without a scope everywhere`() = onEdt {
        open("", "notes.txt")

        fixture.completeBasic()

        // `scoped` names only the extension's own language.
        assertEquals(listOf("anywhere"), fixture.lookupElementStrings)
    }

    @Test
    fun `a snippet's stops are walked as a live template`() = onEdt {
        open("forr", "main.snip.txt")

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("for (<selection>item<caret></selection> in items) {\n    \n}")
        val state = TemplateManagerImpl.getTemplateState(fixture.editor)!!
        state.nextTab()
        fixture.checkResult("for (item in <selection>items<caret></selection>) {\n    \n}")
        state.nextTab()
        fixture.checkResult("for (item in items) {\n    <caret>\n}")
    }

    @Test
    fun `a body's lines are indented to the line the snippet goes into`() = onEdt {
        open("  forr", "main.snip.txt")

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        TemplateManagerImpl.getTemplateState(fixture.editor)!!.gotoEnd(false)

        assertEquals("  for (item in items) {\n      \n  }", fixture.editor.document.text)
    }

    @Test
    fun `a variable takes its value, transformed`() = onEdt {
        open("cla", "widget.snip.txt")

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("class Widget<caret>")
    }

    @Test
    fun `a prefix that is not a word is matched from where it starts`() = onEdt {
        open("x = !h", "main.snip.txt")

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("x = <html><caret></html>")
    }

    @Test
    fun `none in editor snippetSuggestions turns snippets off`() = onEdt {
        servers.settingsForTest = buildJsonObject { put("editor.snippetSuggestions", "none") }
        open("fo", "main.snip.txt")

        fixture.completeBasic()

        assertEquals(emptyList(), fixture.lookupElementStrings.orEmpty())
    }

    @Test
    fun `the setting is read for the file's language`() = onEdt {
        servers.settingsForTest = buildJsonObject {
            put("[plaintext]", buildJsonObject { put("editor.snippetSuggestions", "none") })
        }
        open("fo", "main.snip.txt")

        fixture.completeBasic()

        assertEquals(listOf("forr"), fixture.lookupElementStrings)
    }

    private fun open(text: String, name: String) {
        val path = directory.resolve(name).also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.textLength)
    }

    private fun installVsix() {
        val manifest = buildJsonObject {
            put("publisher", "codebridge"); put("name", "probe-snippets"); put("version", "1.0.0")
            put("engines", buildJsonObject { put("vscode", "^1.90.0") })
            put("contributes", buildJsonObject {
                put("languages", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "codebridgesnip")
                        put("extensions", buildJsonArray { add(JsonPrimitive(".snip.txt")) })
                    })
                })
                put("snippets", buildJsonArray {
                    add(buildJsonObject { put("language", "codebridgesnip"); put("path", "./snippets/snip.json") })
                    add(buildJsonObject { put("path", "./snippets/global.code-snippets") })
                })
            })
        }
        val language = """
            {
              // Comments are allowed, as in every VS Code JSON file.
              "For loop": {
                "prefix": "forr",
                "body": ["for (${'$'}{1:item} in ${'$'}{2:items}) {", "\t${'$'}0", "}"],
                "description": "Loop over a collection"
              },
              "Class": {
                "prefix": ["cla"],
                "body": "class ${'$'}{TM_FILENAME_BASE/([^.]*).*/${'$'}{1:/capitalize}/}"
              },
              "Html": { "prefix": "!html", "body": "<html>${'$'}0</html>" }
            }
        """.trimIndent()
        val global = """
            {
              "Anywhere": { "prefix": "anywhere", "body": "anywhere" },
              "Scoped": { "prefix": "scoped", "body": "scoped", "scope": "codebridgesnip" }
            }
        """.trimIndent()
        @Suppress("HttpUrlsUsage") // an XML namespace, not a link
        val xml = """<PackageManifest xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">""" +
                """<Metadata><Identity Id="probe-snippets"/></Metadata></PackageManifest>"""
        val vsix = Files.createTempFile(directory, "probe-", ".vsix")
        ZipArchiveOutputStream(vsix).use { zip ->
            for ((name, bytes) in listOf(
                "extension/package.json" to manifest.toString().toByteArray(),
                "extension.vsixmanifest" to xml.toByteArray(),
                "extension/snippets/snip.json" to language.toByteArray(),
                "extension/snippets/global.code-snippets" to global.toByteArray(),
            )) {
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
        }
        ExtensionInstallationService.getInstance().install(vsix) {}
    }

    private val servers get() = fixture.project.service<HostedLanguageServers>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-snippets") }

    private companion object {
        const val ID = "codebridge.probe-snippets"
        val CONTRIBUTORS = ExtensionPointName<CompletionContributorEP>("com.intellij.completion.contributor")
    }
}
