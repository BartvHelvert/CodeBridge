package me.helvert.codebridge.ide

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.testFramework.registerOrReplaceServiceInstance
import me.helvert.codebridge.language.HostedLanguageFeatures
import me.helvert.codebridge.language.CodeBridgeCompletionContributor
import me.helvert.codebridge.language.CodeBridgeCompletionLanguages
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Completions as the editor draws them, end to end from the wire payload.
 *
 * `CompletionModelTest` covers reading the reply and `SnippetsTest` covers reducing a snippet; what
 * neither can see is the part that decides whether any of it appears -- the prefix. For a plain-text
 * file IntelliJ would work the word boundary out itself, and a lookup that quietly filters out every
 * item the server offered looks exactly like a server that offered nothing.
 *
 * No host runs here, so the fixture answers `$provideCompletionItems` as an extension would.
 */
class CompletionTest : EditorFixture() {

    private var autocomplete = false

    @BeforeEach
    fun registerContributor() = onEdt {
        sent.clear()
        // A light fixture starts the platform, not this plugin, so the contributor is registered by
        // hand; `the plugin registers the contributor` covers the declaration itself.
        val descriptor = DefaultPluginDescriptor(
            PluginId.getId("me.helvert.codebridge"),
            CodeBridgeCompletionContributor::class.java.classLoader,
        )
        CONTRIBUTORS.point.registerExtension(
            CompletionContributorEP("TEXT", CodeBridgeCompletionContributor::class.java.name, descriptor),
            fixture.testRootDisposable,
        )
        // Likewise the backend's own runner of completion commands, without which a chosen item's
        // command would be asked for over the RPC, which a light fixture does not have.
        com.intellij.openapi.application.ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            me.helvert.codebridge.completion.CodeBridgeCompletionCommandRunner::class.java,
            me.helvert.codebridge.language.LocalCompletionCommandRunner(),
            fixture.testRootDisposable,
        )
        // A lookup with one item would otherwise be inserted and never shown, and the assertions
        // here are about what was offered rather than about what IntelliJ does with a single offer.
        autocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        HostedLanguageFeatures.getInstance(fixture.project).hostStopped()
        // Templates otherwise finish the moment they start in a test, and a snippet's stops with them.
        TemplateManagerImpl.setTemplateTesting(fixture.testRootDisposable)
    }

    @AfterEach
    fun restoreSettings() {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        // Application-scoped, so it would otherwise follow the next test in this JVM.
        CodeBridgeCompletionLanguages.getInstance().forgetForTest()
    }

    @Test
    fun `the plugin registers the contributor`() {
        val descriptor =
            Files.readString(Path.of("src", "main", "resources", "CodeBridge.backend.xml").toAbsolutePath())
        assertTrue(
            descriptor.contains("implementationClass=" + QUOTE + "me.helvert.codebridge.language.CodeBridgeCompletionContributor" + QUOTE),
            "Nothing in a light fixture would notice this being deleted",
        )
        // Only `TEXT` is declared. Every other language a claimed file can get is registered at
        // runtime by `CodeBridgeCompletionLanguages`, because which ones exist is not known until an
        // extension is installed; `CompletionLanguagesTest` covers that half.
        assertTrue(
            descriptor.contains(
                "<completion.contributor language=" + QUOTE + "TEXT" + QUOTE +
                        " implementationClass=" + QUOTE + "me.helvert.codebridge.language.CodeBridgeCompletionContributor" + QUOTE + "/>",
            ),
            "Completion must stay declared for TEXT",
        )
    }

    @Test
    fun `nothing is asked of the host when no extension offered to complete`() = onEdt {
        open("prin")
        features.callHostForTest { _, _, _, _ -> EMPTY }

        fixture.completeBasic()

        assertTrue(sent.isEmpty(), "A file no extension claimed must not reach the host: $sent")
    }

    @Test
    fun `the position sent is the caret, in VS Code's one-based terms`() = onEdt {
        open("first line\nprin")
        answer(item("println"))

        fixture.completeBasic()

        val provide = sent.first { it.contains("lineNumber") }
        assertTrue(provide.contains(POSITION), "Line 2, one past the fourth character: $provide")
    }

    @Test
    fun `the extension's prefix is used, not the one IntelliJ would guess`() = onEdt {
        // A dot is not a word character, so IntelliJ's own prefix here would be empty and the item
        // would be filtered out. The range the extension sent says the prefix is `os.pa`.
        open("os.pa")
        answer(item("os.path"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("os.path"), fixture.lookupElementStrings)
    }

    @Test
    fun `an item is matched by its label, not by the text it inserts`() = onEdt {
        // rust-analyzer's `pd` inserts an `eprintln!`; VS Code does not offer it for `print`.
        open("print")
        answer(item("pd", insert = "eprintln!(x)"), item("println"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("println"), fixture.lookupElementStrings)
    }

    @Test
    fun `an item's filter text is matched in place of its label`() = onEdt {
        open("wr")
        answer(item("println", filter = "write"), item("wrap"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("write", "wrap"), fixture.lookupElementStrings)
    }

    @Test
    fun `an item inserts its text, not the label it was matched by`() = onEdt {
        open("p")
        answer(item("pd", insert = "eprintln!(x)"), startColumn = 1)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("eprintln!(x)<caret>")
    }

    @Test
    fun `items are ranked by their sort text, not by the order they arrived in`() = onEdt {
        open("prin")
        answer(
            item("prinA", sort = "c"), item("prinB", sort = "A"), item("prinC", sort = "b"),
            startColumn = 1,
        )

        fixture.completeBasic()

        // Ignoring case, as VS Code does: `A` before `b` before `c`.
        assertEquals(listOf("prinB", "prinC", "prinA"), fixture.lookupElementStrings)
    }

    @Test
    fun `an item matching from its start goes above one matching further in`() = onEdt {
        // rust-analyzer ranks both macros alike, so their labels would put `eprintln!` first.
        open("println")
        answer(
            item("eprintln!(…)", filter = "eprintln!", sort = "7fffffff"),
            item("println!(…)", filter = "println!", sort = "7fffffff"),
            startColumn = 1,
        )

        fixture.completeBasic()

        assertEquals(listOf("println!", "eprintln!"), fixture.lookupElementStrings)
    }

    @Test
    fun `items without sort text are ranked by their label`() = onEdt {
        open("prin")
        answer(item("prinZ"), item("prinM"), item("prinA"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("prinA", "prinM", "prinZ"), fixture.lookupElementStrings)
    }

    @Test
    fun `words from the document are not offered once an extension answered`() = onEdt {
        // Typed out in full, the word would be an exact match, which the lookup lifts above any
        // ranking; VS Code offers words only when no provider answered.
        open("println\nprintln")
        answer(item("println!"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("println!"), fixture.lookupElementStrings)
    }

    @Test
    fun `words from the document are still offered when the extension offered nothing`() = onEdt {
        open("printline\nprin")
        features.handle(
            "MainThreadLanguageFeatures", $$"$registerCompletionsProvider",
            """[3,[{"language":"codebridgebundled"}],["."],false,{"value":"fixture"}]""",
        )
        features.callHostForTest { _, _, _, _ -> EMPTY }

        fixture.completeBasic()

        assertEquals(listOf("printline"), fixture.lookupElementStrings)
    }

    @Test
    fun `the IDE's own suggestions come after every item the extension offered`() = onEdt {
        // In a plain-text file the IDE offers words from elsewhere in it; with no priority of their
        // own, they used to tie with the extension's best item and go above its second.
        registerContributor(WordContributor::class.java)
        open("prin")
        answer(item("prinA"), item("prinB"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("prinA", "prinB", WordContributor.WORD), fixture.lookupElementStrings)
    }

    @Test
    fun `without a range, the word before the caret is what gets replaced`() = onEdt {
        // IntelliJ finds no prefix of its own in plain text, and would leave `prin` in front of the
        // accepted item. The host normally sends the word as the default range; this is the fallback.
        open("x.prin")
        answer(item("println"), range = false)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("x.println<caret>")
    }

    @Test
    fun `the caret lands on the snippet's final tab stop`() = onEdt {
        // IntelliJ leaves the caret after what it inserted, which for a call is past its parenthesis.
        open("prin")
        answer(item("println", insert = "println($0)", snippet = true), startColumn = 1)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("println(<caret>)")
    }

    @Test
    fun `a placeholder's default text is selected, so typing replaces it`() = onEdt {
        open("i")
        answer(item("if", insert = $$"if (${1:condition})", snippet = true), startColumn = 1)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        fixture.checkResult("if (<selection>condition<caret></selection>)")
    }

    @Test
    fun `a final stop written twice puts a caret on each, typed into at once`() = onEdt {
        // rust-analyzer's `pd`.
        open("pd")
        answer(item("pd", insert = $$"eprintln!(\"$0 = {:?}\", $0);", snippet = true), startColumn = 1)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        fixture.type("x")

        assertEquals("eprintln!(\"x = {:?}\", x);", fixture.editor.document.text)
        assertEquals(listOf(12, 23), fixture.editor.caretModel.allCarets.map { it.offset })
    }

    @Test
    fun `leaving the last stop puts a caret on every final stop`() = onEdt {
        open("f")
        answer(item("f", insert = $$"f(${1:a}, $0, $0)", snippet = true), startColumn = 1)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        TemplateManagerImpl.getTemplateState(fixture.editor)!!.nextTab()

        assertEquals(listOf(5, 7), fixture.editor.caretModel.allCarets.map { it.offset })
    }

    @Test
    fun `tab walks the stops in number order and finishes on the final one`() = onEdt {
        open("f")
        answer(item("f", insert = $$"f(${2:b}, ${1:a}) {$0}", snippet = true), startColumn = 1)

        fixture.completeBasic()
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        fixture.type("x")
        TemplateManagerImpl.getTemplateState(fixture.editor)!!.nextTab()
        fixture.type("y")
        TemplateManagerImpl.getTemplateState(fixture.editor)!!.nextTab()

        fixture.checkResult("f(y, x) {<caret>}")
    }

    @Test
    fun `a file the IDE has its own language for is completed too`() = onEdt {
        // The registration under test is the dynamic one: `.json` never reaches the file type
        // detector, so this file is JSON, and the `TEXT` contributor registered above is never
        // asked for it. The items were fetched and silently dropped before this existed.
        open("os.pa", name = "example.json")
        CodeBridgeCompletionLanguages.getInstance().observe(fixture.file.virtualFile.fileType)
        answer(item("os.path"), startColumn = 1)

        fixture.completeBasic()

        assertEquals(listOf("os.path"), fixture.lookupElementStrings)
    }

    @Test
    fun `the cache the host is holding is handed back`() = onEdt {
        // Left unreleased, the host keeps every item of every lookup the user ever opened.
        open("prin")
        answer(item("println"))

        fixture.completeBasic()

        assertTrue(sent.any { it == "[3,7]" }, "Expected cache 7 of provider 3 to be released: $sent")
    }

    @Test
    fun `a chosen item's command is run, and its list kept until then`() {
        // CodeLLDB's "CodeLLDB: Cargo ...": nothing to insert, a command the host keeps to do the work.
        onEdt {
            open("prin")
            answer("""{"a":"println","h":"println","n":"5","o":"__vsc_delegate"}""", startColumn = 1)
            fixture.completeBasic()
            // Inserted by the client in split mode, so the filter text is not drawn and then replaced.
            val element = fixture.lookupElements!!.single() as com.intellij.codeInsight.lookup.LookupElementDecorator<*>
            val handler = (element.delegate as LookupElementBuilder).insertHandler
            assertTrue(
                handler is FrontendFriendlyInsertHandler,
                "Inserted by $handler"
            )
        }

        assertTrue(sent.none { it == "[3,7]" }, "A list whose command can still be chosen was released: $sent")
        onEdt { fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR) }

        // Run on the worker that sends edits, after the insertion has been sent.
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (sent.none { it.startsWith("[\"__vsc_delegate\"") } && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(
            sent.any { it == """["__vsc_delegate","5"]""" },
            "Expected the command to run with its handle: $sent"
        )
        onEdt { assertEquals("println", fixture.editor.document.text) }
    }

    @Test
    fun `the selected item's documentation is resolved, and its list kept for that`() {
        // rust-analyzer leaves every item's documentation out of the list and fills it in on resolve.
        lateinit var element: com.intellij.codeInsight.lookup.LookupElement
        onEdt {
            open("pus")
            answer(
                """{"a":"push","c":"fn push"}""", startColumn = 1, resolves = true,
                resolved = """{"a":"push","c":"pub fn push(&mut self, value: T)","d":{"value":"Appends an *element*."}}"""
            )
            fixture.completeBasic()
            element = fixture.lookupElements!!.single()
        }
        assertTrue(sent.none { it == "[3,7]" }, "A list whose items can still be resolved was released: $sent")

        val target = com.intellij.openapi.application.runReadActionBlocking {
            me.helvert.codebridge.language.CodeBridgeCompletionDocumentation().documentationTarget(fixture.file, element, 0)
        }

        @Suppress("OverrideOnly") // the platform calls createPointer the same way
        val html =
            computeDocumentationBlocking(target!!.createPointer())?.html.orEmpty()

        assertTrue(sent.any { it == "[3,[7,0]]" }, "Expected item 0 of cache 7 to be resolved: $sent")
        assertTrue("pub fn push" in html, "The resolved detail is missing: $html")
        assertTrue("<em>element</em>" in html, "The documentation was not rendered as markdown: $html")
    }

    @Test
    fun `an item whose provider does not resolve is documented from the list alone`() {
        lateinit var element: com.intellij.codeInsight.lookup.LookupElement
        onEdt {
            open("pus")
            answer("""{"a":"push","c":"fn push","d":"plain *text*"}""", startColumn = 1)
            fixture.completeBasic()
            element = fixture.lookupElements!!.single()
        }
        val target = com.intellij.openapi.application.runReadActionBlocking {
            me.helvert.codebridge.language.CodeBridgeCompletionDocumentation().documentationTarget(fixture.file, element, 0)
        }

        @Suppress("OverrideOnly") // the platform calls createPointer the same way
        val html = computeDocumentationBlocking(target!!.createPointer())?.html.orEmpty()

        assertTrue(sent.none { it.startsWith("[3,[") }, "Nothing should be resolved: $sent")
        assertTrue(
            "fn push" in html && "plain *text*" in html,
            "Expected the detail and the plain documentation: $html"
        )
    }

    /**
     * Registers one completion provider and answers with [items].
     *
     * The default range runs from [startColumn] to the caret, which is what the extension would
     * send: the word it believes is being completed. Without a [range], the reply names none at all.
     */
    private fun answer(
        vararg items: String,
        startColumn: Int? = null,
        range: Boolean = true,
        resolves: Boolean = false,
        resolved: String = EMPTY,
    ) {
        features.handle(
            "MainThreadLanguageFeatures", $$"$registerCompletionsProvider",
            """[3,[{"language":"codebridgebundled"}],["."],$resolves,{"value":"fixture"}]""",
        )
        val document = fixture.editor.document
        val caret = document.textLength
        val line = document.getLineNumber(caret) + 1
        val column = caret - document.getLineStartOffset(line - 1) + 1
        val span = """{"startLineNumber":$line,"startColumn":${startColumn ?: column},""" +
                """"endLineNumber":$line,"endColumn":$column}"""
        val default = if (range) """"a":{"insert":$span,"replace":$span},""" else ""
        val reply = """{"x":7,$default"b":[${items.joinToString(",")}]}"""
        features.callHostForTest { _, method, args, _ ->
            sent.add(args)
            
                when (method) {
                    $$"$provideCompletionItems" -> reply
                    $$"$resolveCompletionItem" -> resolved
                    else -> EMPTY
                }
            
        }
    }

    private fun item(
        label: String, insert: String = label, snippet: Boolean = false, filter: String? = null, sort: String? = null,
    ): String {
        val escaped = insert.replace("\\", "\\\\").replace(QUOTE, "\\" + QUOTE)
        return "{" + json("a", label) + "," + json("h", escaped) +
                (if (filter != null) "," + json("f", filter) else "") +
                (if (sort != null) "," + json("e", sort) else "") +
                (if (snippet) ",\"i\":4" else "") + "}"
    }

    /** Registers [contributor] for plain text, as the plugin's own is registered for every test. */
    private fun registerContributor(contributor: Class<out CompletionContributor>) {
        val descriptor = DefaultPluginDescriptor(PluginId.getId("me.helvert.codebridge.test"), contributor.classLoader)
        CONTRIBUTORS.point.registerExtension(
            CompletionContributorEP("TEXT", contributor.name, descriptor),
            fixture.testRootDisposable,
        )
    }

    /** Stands in for the IDE's word completion: one suggestion, at the default priority. */
    class WordContributor : CompletionContributor() {
        override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
            result.withPrefixMatcher("prin").addElement(LookupElementBuilder.create(WORD))
        }

        companion object {
            const val WORD = "prinW"
        }
    }

    private fun json(key: String, value: String) = "$QUOTE$key$QUOTE:$QUOTE$value$QUOTE"

    /**
     * A real file, not the fixture's in-memory one: the URI is built from `VirtualFile.toNioPath`,
     * which the temporary filesystem a light fixture uses does not support.
     */
    private fun open(text: String, name: String = "example.txt") {
        val path = directory.resolve(name).also { it.writeText(text) }
        val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
        fixture.openFileInEditor(file)
        mirror(file)
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.textLength)
    }

    private val features get() = HostedLanguageFeatures.getInstance(fixture.project)

    /**
     * Gives the host a copy of [file], as opening a claimed file does: providers are asked only
     * about documents the host holds. As the selector's language, so it is the file's claim that
     * decides who is asked.
     */
    private fun mirror(file: com.intellij.openapi.vfs.VirtualFile) {
        features.mirrorForTest(me.helvert.codebridge.exthost.ExtensionHostDocuments { _, _, _ -> "" })
        features.documentOpened(
            file,
            "codebridgebundled",
            HostedLanguageFeatures.Snapshot(fixture.editor.document.text, false)
        )
    }

    private val sent = ConcurrentLinkedQueue<String>()

    private val directory: Path by lazy { Files.createTempDirectory("codebridge-completion") }

    private companion object {
        val CONTRIBUTORS = ExtensionPointName<CompletionContributorEP>("com.intellij.completion.contributor")

        const val QUOTE = "\""
        const val EMPTY = "null"
        const val POSITION = """{"lineNumber":2,"column":5}"""
    }
}
