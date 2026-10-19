package me.helvert.codebridge.exthost

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.Completions
import me.helvert.codebridge.exthost.protocol.payload.LanguageFeatures
import me.helvert.codebridge.exthost.protocol.payload.Position
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.manifest.VsixManifestParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Integration coverage for language features through the real extension host.
 *
 * The earlier stdio proxy wrapped the extension's pipes, and the RPC bridge instrumented its client
 * library. Each depended on something private to the extension -- a transport in one case, a
 * library version in the other. The workbench needs neither:
 * providers are registered to it over `MainThreadLanguageFeatures`, diagnostics are pushed to it
 * over `MainThreadDiagnostics`, and it asks for a hover with `ExtHostLanguageFeatures.$provideHover`.
 *
 * This test drives exactly that, against the bundled fixture -- a webpacked extension with no
 * `node_modules`, which the RPC path cannot instrument at all. Nothing here patches the runtime,
 * patches a client, or speaks LSP; the extension's own language client talks to its own server
 * privately throughout, as it would in VS Code.
 */
class LanguageFeaturesIntegrationTest {

    @Test
    fun `the workbench protocol reaches a bundled extension's hover and diagnostics`() {
        check("lsp-bundled-extension", "codebridgebundled", "probe.codebridgebundled")
    }

    @Test
    fun `a server on a socket is reached the same way`() {
        // The transport the stdio proxy could never see. Through the workbench there is nothing to
        // intercept, so how the extension reaches its server stops being the bridge's business.
        assumeTrue(
            Files.isDirectory(Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", "lsp-socket-extension", "node_modules").toAbsolutePath().normalize()),
            "Run npm ci in the lsp-socket-extension fixture",
        )
        check("lsp-socket-extension", "codebridgesocket", "probe.codebridgesocket")
    }

    private fun check(fixtureName: String, language: String, probe: String) {
        assumeTrue(
            System.getenv(TestRuntime.FETCH_ENV) == "1",
            "Set ${TestRuntime.FETCH_ENV}=1 to run the real host integration test",
        )
        val installation = TestRuntime.orSkip()
        assumeTrue(installation != null, "Requires the managed runtime or ${VsCodeInstallation.OVERRIDE_ENV}")

        val fixture = Path.of(System.getProperty("codebridge.test.fixtures"), "exthost", fixtureName).toAbsolutePath().normalize()
        val manifest = assertNotNull(VsixManifestParser.parse(Files.readString(fixture.resolve("package.json"))).manifest)
        val root = Files.createTempDirectory("codebridge-language-features")
        val file = fixture.resolve(probe)
        val uri = file.toUri().toString()

        val providers = ConcurrentLinkedQueue<Int>()
        val completionProviders = ConcurrentLinkedQueue<Int>()
        val definitionProviders = ConcurrentLinkedQueue<Int>()
        val diagnostics = ConcurrentLinkedQueue<JsonElement>()

        val handler = MainThreadHandler { call ->
            when {
                call.method == $$"$getInitialState" -> """{"isFocused":true,"isActive":true}"""
                // The host iterates this; answering null raises "n is not iterable" inside it.
                call.method == $$"$getTools" -> "[]"
                // Every provider the extension registers arrives here, whatever it is backed by.
                call.service == "MainThreadLanguageFeatures" && call.method == $$"$registerHoverProvider" -> {
                    providers.add(Json.parseToJsonElement(call.argsJson).jsonArray[0].jsonPrimitive.int)
                    null
                }
                call.service == "MainThreadLanguageFeatures" && call.method == $$"$registerCompletionsProvider" -> {
                    completionProviders.add(Json.parseToJsonElement(call.argsJson).jsonArray[0].jsonPrimitive.int)
                    null
                }
                call.service == "MainThreadLanguageFeatures" && call.method == $$"$registerDefinitionSupport" -> {
                    definitionProviders.add(Json.parseToJsonElement(call.argsJson).jsonArray[0].jsonPrimitive.int)
                    null
                }
                // Answered the way the plugin answers it: the reply is the channel id itself, and
                // every later call names the channel by it.
                call.service == "MainThreadOutputService" && call.method == $$"$register" -> "\"channel-1\""
                // Diagnostics need no provider protocol at all: extensions push them.
                call.service == "MainThreadDiagnostics" && call.method == $$"$changeMany" -> {
                    diagnostics.add(Json.parseToJsonElement(call.argsJson))
                    null
                }
                else -> null
            }
        }

        ExtensionHostSession(installation!!, listOf(ExtensionHostInitData.describeExtension(manifest, fixture)), root, handler)
            .use { host ->
                host.start()
                assertTrue(host.awaitInitialized(30, TimeUnit.SECONDS))

                val identifier = ExtensionHostInitData.identifier(manifest.id)
                val reason = """{"startup":false,"activationEvent":"onLanguage:$language","extensionId":$identifier}"""
                // Not awaited: activation resolves only once the client has started.
                CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        host.call("ExtHostExtensionService", $$"$activate", """[$identifier,$reason]""")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }

                // The extension's client registers its providers while starting. Its own server is
                // launched, initialized and spoken to entirely by the extension.
                val handle = await("a registered hover provider") { providers.peek() }

                // Give the host the document, exactly as the workbench would. The client's own
                // document feature then sends didOpen upstream and publishes what comes back.
                val documents = ExtensionHostDocuments(host)
                documents.open("test", uri, language, "hello from IntelliJ", dirty = false)

                val published = await("published diagnostics") { diagnostics.peek() }
                assertTrue(
                    published.toString().contains(uri.substringAfterLast('/')),
                    "Diagnostics should name the document that was opened: $published",
                )

                // What the workbench calls when the user hovers. The token is appended by the host,
                // so it is absent from the arguments and the request is sent as cancellable.
                val hover = runBlocking { withTimeout(30.seconds) { host.call(
                    "ExtHostLanguageFeatures", LanguageFeatures.PROVIDE_HOVER,
                    LanguageFeatures.provideHover(handle, UriComponents.of(uri), Position(1, 1)),
                    cancellable = true,
                ) } }

                // Results arrive in VS Code's editor shapes, not LSP's: 1-based
                // `startLineNumber`/`startColumn`, and a MarkdownString whose plaintext has had its
                // spaces escaped to `&nbsp;`. Translating that is the per-feature cost of this route.
                val contents = Json.parseToJsonElement(hover).jsonObject
                    .getValue("contents").jsonArray.single().jsonObject
                    .getValue("value").jsonPrimitive.content.replace("&nbsp;", " ")
                assertTrue(
                    contents.contains("server PID"),
                    "The hover must come from the extension's own language server: $contents",
                )
                // Proof the document reached the server through the host's own model: the fixture
                // answers with the version and line it was actually sent.
                assertTrue(contents.endsWith("version 1: hello from IntelliJ"), "Stale document: $contents")
                assertEquals(1, Json.parseToJsonElement(hover).jsonObject
                    .getValue("range").jsonObject.getValue("startLineNumber").jsonPrimitive.int)

                // The rest of document synchronization, checked the same way. A typed edit that did
                // not reach the server reads back as the old text rather than as an error, which is
                // the failure this whole path is easiest to get silently wrong in.
                assertTrue(documents.change("test", uri, "edited in IntelliJ", dirty = true),
                    "New text has to be sent on")
                await("the edit to reach the server") {
                    hoverOf(host, handle, uri).takeIf { it.endsWith("version 2: edited in IntelliJ") }
                }

                // Re-sending the text the host already has is not an edit. Several IntelliJ
                // endpoints report one keystroke, and a version that moved for each of them would
                // outrun what the server was told.
                assertFalse(documents.change("test", uri, "edited in IntelliJ", dirty = true))
                assertEquals(2, documents.versionOf(uri))

                // The host throws `unknown document` for a URI it cannot match, so this pins the
                // spelling the whole round trip depends on as much as it pins the save itself.
                documents.save("test", uri)
                assertTrue(hoverOf(host, handle, uri).endsWith("version 2: edited in IntelliJ"),
                    "Saving must not disturb the document the server holds")

                // Completion, through the same route and against the same synchronized document.
                // The fixture answers with the version it holds, so an item computed against a
                // stale buffer is a wrong label rather than a silence.
                val completions = await("a registered completion provider") { completionProviders.peek() }
                val offered = completionsOf(host, completions, uri)
                val items = offered.getValue("b").jsonArray.map { it.jsonObject }
                assertEquals("version-2", items[0].getValue("a").jsonPrimitive.content,
                    "The completion has to come from the edited document: $offered")
                assertEquals("edited in IntelliJ", items[0].getValue("c").jsonPrimitive.content,
                    "The detail carries the line the server actually holds")
                // A snippet arrives as a rule rather than as text, and nothing else says so.
                assertEquals(snippetRule, items[1].getValue("i").jsonPrimitive.int and snippetRule,
                    "The second item was sent as a snippet: $offered")
                // The cache the items live in, which has to be handed back or the host keeps them.
                assertTrue(offered.containsKey("x"), "Expected a cache id: $offered")
                runBlocking { withTimeout(30.seconds) { host.call(
                    "ExtHostLanguageFeatures", $$"$releaseCompletionItems",
                    "[$completions,${offered.getValue("x").jsonPrimitive.int}]",
                ) } }

                // Where the extension says a symbol is defined, read back through the reader the
                // plugin uses. The fixture answers with a link rather than a plain location, and
                // puts the selection at the column of the version it holds, so a definition
                // computed against a stale buffer is a wrong caret rather than a silence.
                val definitionHandle = await("a registered definition provider") { definitionProviders.peek() }
                val located = LanguageFeatures.definitionLinks(definitionsOf(host, definitionHandle, uri)).single()
                // Compared as keys: the host answers with VS Code's lowercase Windows drive, and
                // telling those two spellings apart is the whole reason `key` exists.
                assertEquals(UriComponents.key(uri), UriComponents.key(located.resource.uri))
                assertEquals(1, located.target.startColumn, "The definition itself starts at column 1")
                // Version 2, at LSP character 2, is VS Code column 3: the conversion the whole
                // round trip turns on, and the one nothing else here would catch.
                assertEquals(3, located.caret.column, "The caret belongs on the selection, in the edited document")

                // Closing withdraws it from the host, so there is nothing left to hover over.
                documents.close("test", uri)
                assertFails { hoverOf(host, handle, uri) }

            }
    }

    /** One definition request, as the workbench makes it. */
    private fun definitionsOf(host: ExtensionHostSession, handle: Int, uri: String): String =
        runBlocking { withTimeout(30.seconds) { host.call(
            "ExtHostLanguageFeatures", LanguageFeatures.PROVIDE_DEFINITION,
            LanguageFeatures.provideDefinition(handle, UriComponents.of(uri), Position(1, 1)),
            cancellable = true,
        ) } }

    /** One completion request, as the workbench makes it. */
    private fun completionsOf(host: ExtensionHostSession, handle: Int, uri: String): JsonObject {
        val reply = runBlocking { withTimeout(30.seconds) { host.call(
            "ExtHostLanguageFeatures", Completions.PROVIDE,
            LanguageFeatures.provideCompletions(handle, UriComponents.of(uri), Position(1, 1)),
            cancellable = true,
        ) } }
        return Json.parseToJsonElement(reply).jsonObject
    }

    /** One hover, as the workbench asks for it, reduced to the text the fixture's server produced. */
    private fun hoverOf(host: ExtensionHostSession, handle: Int, uri: String): String {
        val reply = runBlocking { withTimeout(30.seconds) { host.call(
            "ExtHostLanguageFeatures", LanguageFeatures.PROVIDE_HOVER,
            LanguageFeatures.provideHover(handle, UriComponents.of(uri), Position(1, 1)),
            cancellable = true,
        ) } }
        return Json.parseToJsonElement(reply).jsonObject
            .getValue("contents").jsonArray.single().jsonObject
            .getValue("value").jsonPrimitive.content.replace("&nbsp;", " ")
    }

    /** `InsertTextRules.InsertAsSnippet`, as the workbench numbers it. */
    private val snippetRule = 4

    /** The extension starts its client asynchronously; nothing signals when a provider appears. */
    private fun <T : Any> await(what: String, poll: () -> T?): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            poll()?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for $what")
    }
}
