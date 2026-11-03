package me.helvert.codebridge.language

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.ExtensionHost
import me.helvert.codebridge.exthost.HostReply
import me.helvert.codebridge.exthost.MainThreadCall
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's extension hosts -- VS Code's Node host, and its web worker host for the extensions
 * that run only in a browser -- behind one front, for workbench services written for a single host.
 *
 * VS Code gives each host a set of main-thread services of its own. Here the services are shared,
 * so the two hosts' own numbering -- the handles each gives its providers, counting from 0 in both --
 * is told apart where calls cross: a number the worker host gave out is moved up by [WORKER_OFFSET]
 * on its way in ([incoming]), and back down on its way out to the worker. What a host names with a
 * string of its own -- a command, a tree view, a webview -- is remembered as that host's when it
 * registers it. A call into the hosts goes to the one it names, by either; to the extension's host
 * for an activation; and to every host for news they all keep, such as a document's text, as VS
 * Code's workbench tells every host.
 *
 * The rules are per protocol method, from VS Code's `extHost.protocol.ts`; a method without one goes
 * to the Node host, as everything did before there was a second.
 */
internal class ExtensionHostRouter(
    private val node: ExtensionHost,
    private val worker: ExtensionHost?,
    /** The extensions the worker host runs, by id in lower case. */
    private val workerExtensions: Set<String>,
    /** Where the parts of a call sent to several hosts that nothing waits for finish. */
    private val scope: CoroutineScope,
) : ExtensionHost {

    /** Strings a host named, per kind -- `command`, `treeView` -- and the host that did. */
    private val keys = ConcurrentHashMap<String, ConcurrentHashMap<String, ExtensionHost>>()

    private val hosts: List<ExtensionHost> = listOfNotNull(node, worker)

    /** The Node host's: the project's host ends with it. */
    override val termination: Deferred<Throwable> get() = node.termination

    /** Which host runs [extensionId]. */
    fun hostOf(extensionId: String): ExtensionHost =
        if (worker != null && extensionId.lowercase() in workerExtensions) worker else node

    // ------------------------------------------------------------------------------------ calls in

    /**
     * [call], from host [from], in the main thread's numbering: what the worker host numbered moved
     * up, and the names a host registers remembered as its own. On the host's reader, in order, so a
     * registration is known before anything can be asked of it.
     */
    fun incoming(call: MainThreadCall, from: ExtensionHost): MainThreadCall {
        val rules = IN[call.service]?.let { it[call.method] ?: it[ANY] } ?: return call
        val args = parse(call.argsJson) ?: return call
        var changed = false
        val rewritten = args.toMutableList()
        for (rule in rules) when (rule) {
            is In.Offset -> if (from === worker) {
                val moved = update(rewritten, rule.path) { value -> number(value)?.let { JsonPrimitive(it + WORKER_OFFSET) } }
                changed = changed || moved
            }
            is In.Prefix -> if (from === worker) {
                val moved = update(rewritten, rule.path) { value ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.let { JsonPrimitive(WORKER_PREFIX + it.content) }
                }
                changed = changed || moved
            }
            is In.Remember -> name(at(args, rule.path))?.let { remember(rule.kind, it, from) }
            is In.Forget -> name(at(args, rule.path))?.let { keys[rule.kind]?.remove(it) }
            is In.Latest -> remember(rule.kind, LATEST, from)
        }
        return if (changed) call.copy(argsJson = JsonArray(rewritten).toString()) else call
    }

    // ----------------------------------------------------------------------------------- calls out

    override suspend fun call(
        service: String, method: String, argsJson: String, cancellable: Boolean,
        buffers: Map<Int, ByteArray>, objectBuffers: Map<Int, List<ByteArray>>,
    ): String {
        val send: suspend (ExtensionHost, String) -> String = { host, args -> host.call(service, method, args, cancellable, buffers, objectBuffers) }
        val split = rule(service, method) as? Out.SplitArray
        if (split != null && worker != null) parse(argsJson)?.let { args -> return splitArray(args, split, send) }
        return route(service, method, argsJson, send)
    }

    override suspend fun callForBytes(service: String, method: String, argsJson: String, cancellable: Boolean): ByteArray? =
        route(service, method, argsJson) { host, args -> host.callForBytes(service, method, args, cancellable) }

    override suspend fun callWithBuffers(
        service: String, method: String, argsJson: String, cancellable: Boolean,
        buffers: Map<Int, ByteArray>, objectBuffers: Map<Int, List<ByteArray>>, timesOut: Boolean,
    ): HostReply = route(service, method, argsJson) { host, args ->
        host.callWithBuffers(service, method, args, cancellable, buffers, objectBuffers, timesOut)
    }

    private fun rule(service: String, method: String): Out? = OUT[service]?.let { it[method] ?: it[ANY] }

    /**
     * Sends a call where its rule says, every part of it before this first suspends, and answers
     * with the reply of the host it went to. One sent to several is answered by the first -- the
     * Node host, where it has a part -- without waiting for the rest: news every host keeps, such as
     * an edit, must not wait on a worker host still starting.
     */
    private suspend fun <T> route(service: String, method: String, argsJson: String, send: suspend (ExtensionHost, String) -> T): T {
        val rule = rule(service, method)
        if (worker == null || rule == null) return send(node, argsJson)
        val parts = when (rule) {
            Out.Node, is Out.SplitArray -> listOf(node to argsJson)
            Out.All -> hosts.map { it to argsJson }
            is Out.Handle -> parse(argsJson)?.let { args -> byHandle(args, rule.position) } ?: listOf(node to argsJson)
            is Out.Key -> parse(argsJson)?.let { args -> byKey(args, rule) } ?: listOf(node to argsJson)
            is Out.Latest -> listOf((keys[rule.kind]?.get(LATEST) ?: node) to argsJson)
            is Out.Extension -> listOf(hostOf(parse(argsJson)?.let { extensionId(it.getOrNull(rule.position)) }.orEmpty()) to argsJson)
            is Out.SplitObject -> parse(argsJson)?.let { args -> splitObject(args, rule) } ?: listOf(node to argsJson)
        }
        if (parts.size == 1) return send(parts[0].first, parts[0].second)
        return coroutineScope {
            // Started undispatched, so each part is sent before the next is, and all before this suspends.
            val first = async(start = CoroutineStart.UNDISPATCHED) { send(parts[0].first, parts[0].second) }
            for ((host, args) in parts.drop(1)) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    send(host, args)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LOG.debug("$service.$method failed in an extension host", failure)
                }
            }
            first.await()
        }
    }

    /** The host a handle at [position] belongs to, and the call as that host numbers it. */
    private fun byHandle(args: JsonArray, position: Int): List<Pair<ExtensionHost, String>> {
        val handle = number(args.getOrNull(position)) ?: return listOf(node to args.toString())
        if (handle < WORKER_OFFSET || worker == null) return listOf(node to args.toString())
        val rewritten = args.toMutableList().also { it[position] = JsonPrimitive(handle - WORKER_OFFSET) }
        return listOf(worker to JsonArray(rewritten).toString())
    }

    private fun byKey(args: JsonArray, rule: Out.Key): List<Pair<ExtensionHost, String>> {
        val host = name(at(args, rule.path))?.let(rule.key)?.let { keys[rule.kind]?.get(it) } ?: node
        rule.learn?.let { (kind, path) -> name(at(args, path))?.let { remember(kind, it, host) } }
        val json = args.toString()
        return if (rule.everywhere) hosts.map { it to json } else listOf(host to json)
    }

    /** An object of [Out.SplitObject.kind] keys, each part to the host the keys in it are. */
    private fun splitObject(args: JsonArray, rule: Out.SplitObject): List<Pair<ExtensionHost, String>> {
        val values = args.getOrNull(rule.position) as? JsonObject ?: return listOf(node to args.toString())
        val byHost = values.entries.groupBy { (key, _) -> keys[rule.kind]?.get(key) ?: node }
        return byHost.map { (host, entries) ->
            host to JsonArray(args.toMutableList().also { it[rule.position] = JsonObject(entries.associate { e -> e.key to e.value }) }).toString()
        }.sortedBy { (host, _) -> if (host === node) 0 else 1 }
    }

    /**
     * An array whose elements each name their host by [Out.SplitArray.field], sent in parts and
     * answered with the parts' replies -- arrays, one element per request -- put back in order.
     */
    private suspend fun splitArray(args: JsonArray, rule: Out.SplitArray, send: suspend (ExtensionHost, String) -> String): String {
        val requests = args.getOrNull(rule.position) as? JsonArray ?: return send(node, args.toString())
        val owners = requests.map { request -> name((request as? JsonObject)?.get(rule.field))?.let { keys[rule.kind]?.get(it) } ?: node }
        val byHost = requests.indices.groupBy { owners[it] }
        if (byHost.size == 1) return send(byHost.keys.single(), args.toString())
        return coroutineScope {
            val replies = byHost.map { (host, indices) ->
                val part = JsonArray(args.toMutableList().also { it[rule.position] = JsonArray(indices.map(requests::get)) }).toString()
                indices to async(start = CoroutineStart.UNDISPATCHED) { send(host, part) }
            }
            val ordered = MutableList<JsonElement>(requests.size) { JsonNull }
            for ((indices, reply) in replies) {
                val answers = parse(reply.await()) ?: continue
                indices.forEachIndexed { i, index -> ordered[index] = answers.getOrNull(i) ?: JsonNull }
            }
            JsonArray(ordered).toString()
        }
    }

    private fun remember(kind: String, key: String, host: ExtensionHost) {
        keys.getOrPut(kind) { ConcurrentHashMap() }[key] = host
    }

    // ------------------------------------------------------------------------------------ the rules

    /** What happens to a call from a host on its way in. Paths are argument positions, then keys. */
    private sealed interface In {
        /** A number the worker host gave out, moved up by [WORKER_OFFSET]. */
        data class Offset(val path: List<Any>) : In
        /** A string the worker host made from a counter of its own, given a prefix of its own. */
        data class Prefix(val path: List<Any>) : In
        /** A name the host registered, remembered as its. */
        data class Remember(val kind: String, val path: List<Any>) : In
        data class Forget(val kind: String, val path: List<Any>) : In
        /** The host of the latest such call, which an answer that names nothing goes back to. */
        data class Latest(val kind: String) : In
    }

    /** Where a call into the hosts goes. */
    private sealed interface Out {
        /** Every host, which each keep what it says. */
        data object All : Out
        /** The Node host alone, for what only a host on the machine can answer. */
        data object Node : Out
        /** The host the handle at [position] is, as [incoming] numbered it. */
        data class Handle(val position: Int) : Out
        /**
         * The host that registered the name at [path], as [key] makes it; with [learn], the name at
         * that path is then remembered as the same host's; with [everywhere], it goes to every host,
         * having learned.
         */
        data class Key(
            val kind: String, val path: List<Any>,
            val learn: Pair<String, List<Any>>? = null,
            val everywhere: Boolean = false,
            val key: (String) -> String = { it },
        ) : Out
        /** The host of the latest call of [kind]; see [In.Latest]. */
        data class Latest(val kind: String) : Out
        /** The host of the extension whose identifier is at [position]. */
        data class Extension(val position: Int) : Out
        /** An object of names at [position], split by the host each is. */
        data class SplitObject(val kind: String, val position: Int) : Out
        /** An array of requests at [position], split by the host each one's [field] is. */
        data class SplitArray(val kind: String, val position: Int, val field: String) : Out
    }

    companion object {
        private val LOG = Logger.getInstance(ExtensionHostRouter::class.java)

        /** What the worker host's numbers are moved up by: above anything a host counts to. */
        const val WORKER_OFFSET: Long = 1L shl 30

        /** What the worker host's own counted strings are given in front. */
        private const val WORKER_PREFIX = "worker:"

        /** A rule for every method of a service that has none of its own. */
        private const val ANY = "*"
        private const val LATEST = ""

        private fun p(vararg path: Any): List<Any> = path.toList()
        private fun offset(vararg positions: Int) = positions.map { In.Offset(listOf(it)) }

        private val IN: Map<String, Map<String, List<In>>> = mapOf(
            "MainThreadLanguageFeatures" to mapOf(
                ANY to offset(0),
                $$"$registerCodeLensSupport" to offset(0, 2),
                $$"$registerInlineValuesProvider" to offset(0, 2),
                $$"$registerDocumentSemanticTokensProvider" to offset(0, 3),
                $$"$registerDocumentRangeSemanticTokensProvider" to offset(0, 3),
                $$"$registerInlayHintsProvider" to offset(0, 3),
                $$"$registerFoldingRangeProvider" to offset(0, 3),
            ),
            "MainThreadDocumentContentProviders" to mapOf(
                $$"$registerTextContentProvider" to offset(0), $$"$unregisterTextContentProvider" to offset(0),
            ),
            "MainThreadFileSystem" to mapOf(
                $$"$registerFileSystemProvider" to offset(0), $$"$unregisterProvider" to offset(0), $$"$onFileSystemChange" to offset(0),
            ),
            "MainThreadDecorations" to mapOf(ANY to offset(0)),
            "MainThreadProgress" to mapOf(ANY to offset(0)),
            "MainThreadTask" to mapOf($$"$registerTaskProvider" to offset(0), $$"$unregisterTaskProvider" to offset(0)),
            "MainThreadUrls" to mapOf($$"$registerUriHandler" to offset(0), $$"$unregisterUriHandler" to offset(0)),
            "MainThreadNotebook" to mapOf(
                $$"$registerNotebookSerializer" to offset(0), $$"$unregisterNotebookSerializer" to offset(0),
                $$"$registerNotebookCellStatusBarItemProvider" to offset(0, 1),
                $$"$unregisterNotebookCellStatusBarItemProvider" to offset(0, 1),
                $$"$emitCellStatusBarEvent" to offset(0),
            ),
            "MainThreadNotebookKernels" to mapOf(
                ANY to offset(0),
                $$"$addKernelSourceActionProvider" to offset(0, 1),
                $$"$removeKernelSourceActionProvider" to offset(0, 1),
            ),
            "MainThreadDebugService" to mapOf(
                $$"$registerDebugConfigurationProvider" to offset(5),
                $$"$registerDebugAdapterDescriptorFactory" to offset(1) + In.Remember("debugType", p(0)),
                $$"$unregisterDebugConfigurationProvider" to offset(0),
                $$"$unregisterDebugAdapterDescriptorFactory" to offset(0),
            ),
            "MainThreadQuickOpen" to mapOf(
                $$"$show" to offset(0) + In.Latest("quickPick"),
                $$"$setItems" to offset(0), $$"$setError" to offset(0), $$"$dispose" to offset(0),
                $$"$input" to listOf(In.Latest("inputBox")),
                $$"$createOrUpdate" to listOf(In.Offset(p(0, "id"))),
            ),
            "MainThreadTextEditors" to mapOf(
                $$"$registerTextEditorDecorationType" to listOf(In.Prefix(p(1))),
                $$"$removeTextEditorDecorationType" to listOf(In.Prefix(p(0))),
                $$"$trySetDecorations" to listOf(In.Prefix(p(1))),
                $$"$trySetDecorationsFast" to listOf(In.Prefix(p(1))),
            ),
            "MainThreadCommands" to mapOf(
                $$"$registerCommand" to listOf(In.Remember("command", p(0))),
                $$"$unregisterCommand" to listOf(In.Forget("command", p(0))),
            ),
            "MainThreadTreeViews" to mapOf($$"$registerTreeViewDataProvider" to listOf(In.Remember("treeView", p(0)))),
            "MainThreadWebviewPanels" to mapOf(
                $$"$createWebviewPanel" to listOf(In.Remember("webview", p(1))),
                $$"$registerSerializer" to listOf(In.Remember("webviewSerializer", p(0))),
                $$"$unregisterSerializer" to listOf(In.Forget("webviewSerializer", p(0))),
            ),
            "MainThreadWebviewViews" to mapOf(
                $$"$registerWebviewViewProvider" to listOf(In.Remember("webviewView", p(1))),
                $$"$unregisterWebviewViewProvider" to listOf(In.Forget("webviewView", p(0))),
            ),
            "MainThreadAuthentication" to mapOf(
                $$"$registerAuthenticationProvider" to listOf(In.Remember("authProvider", p(0, "id"))),
                $$"$unregisterAuthenticationProvider" to listOf(In.Forget("authProvider", p(0))),
            ),
            "MainThreadTesting" to mapOf(
                $$"$registerTestController" to listOf(In.Remember("testController", p(0))),
                $$"$unregisterTestController" to listOf(In.Forget("testController", p(0))),
            ),
            "MainThreadTerminalService" to mapOf($$"$createTerminal" to listOf(In.Remember("extHostTerminal", p(0)))),
        )

        /** A test id's controller: what comes before the first `\0`. */
        private val controller: (String) -> String = { it.substringBefore('\u0000') }

        private val OUT: Map<String, Map<String, Out>> = mapOf(
            "ExtHostLanguageFeatures" to mapOf(ANY to Out.Handle(0)),
            "ExtHostDocumentContentProviders" to mapOf(ANY to Out.Handle(0)),
            "ExtHostFileSystem" to mapOf(ANY to Out.Handle(0)),
            "ExtHostDecorations" to mapOf(ANY to Out.Handle(0)),
            "ExtHostProgress" to mapOf(ANY to Out.Handle(0)),
            "ExtHostUrls" to mapOf(ANY to Out.Handle(0)),
            "ExtHostNotebookKernels" to mapOf(ANY to Out.Handle(0)),
            "ExtHostNotebook" to mapOf(
                ANY to Out.All,
                $$"$provideNotebookCellStatusBarItems" to Out.Handle(0),
                $$"$dataToNotebook" to Out.Handle(0), $$"$notebookToData" to Out.Handle(0),
                $$"$saveNotebook" to Out.Handle(0), $$"$searchInNotebooks" to Out.Handle(0),
            ),
            "ExtHostTask" to mapOf(
                ANY to Out.All,
                $$"$provideTasks" to Out.Handle(0), $$"$resolveTask" to Out.Handle(0),
                // Answered from the machine: only the Node host can.
                $$"$resolveVariables" to Out.Node, $$"$findExecutable" to Out.Node,
                $$"$jsonTasksSupported" to Out.Node,
            ),
            "ExtHostDebugService" to mapOf(
                ANY to Out.All,
                $$"$resolveDebugConfiguration" to Out.Handle(0),
                $$"$resolveDebugConfigurationWithSubstitutedVariables" to Out.Handle(0),
                $$"$provideDebugConfigurations" to Out.Handle(0),
                $$"$provideDebugAdapter" to Out.Handle(0),
                // An adapter's session is the host's whose factory makes adapters of its type.
                $$"$startDASession" to Out.Key("debugType", p(1, "type"), learn = "debugAdapter" to p(0)),
                $$"$sendDAMessage" to Out.Key("debugAdapter", p(0)),
                $$"$stopDASession" to Out.Key("debugAdapter", p(0)),
                $$"$substituteVariables" to Out.Node,
                $$"$runInTerminal" to Out.Node,
            ),
            "ExtHostQuickOpen" to mapOf(
                ANY to Out.Handle(0),
                $$"$onItemSelected" to Out.Latest("quickPick"),
                $$"$validateInput" to Out.Latest("inputBox"),
            ),
            "ExtHostCommands" to mapOf($$"$executeContributedCommand" to Out.Key("command", p(0))),
            "ExtHostTreeViews" to mapOf(
                ANY to Out.Key("treeView", p(0)),
                $$"$releaseDragOperation" to Out.All,
            ),
            "ExtHostWebviews" to mapOf(ANY to Out.Key("webview", p(0))),
            "ExtHostWebviewPanels" to mapOf(
                $$"$onDidDisposeWebviewPanel" to Out.Key("webview", p(0)),
                $$"$onDidChangeWebviewPanelViewStates" to Out.SplitObject("webview", 0),
                $$"$deserializeWebviewPanel" to Out.Key("webviewSerializer", p(1), learn = "webview" to p(0)),
            ),
            "ExtHostWebviewViews" to mapOf(
                $$"$resolveWebviewView" to Out.Key("webviewView", p(1), learn = "webview" to p(0)),
                $$"$onDidChangeWebviewViewVisibility" to Out.Key("webview", p(0)),
                $$"$disposeWebviewView" to Out.Key("webview", p(0)),
            ),
            "ExtHostAuthentication" to mapOf(
                ANY to Out.Key("authProvider", p(0)),
                $$"$onDidChangeAuthenticationSessions" to Out.All,
                $$"$onDidUnregisterAuthenticationProvider" to Out.All,
            ),
            "ExtHostTesting" to mapOf(
                ANY to Out.All,
                $$"$runControllerTests" to Out.SplitArray("testController", 0, "controllerId"),
                $$"$startContinuousRun" to Out.SplitArray("testController", 0, "controllerId"),
                $$"$configureRunProfile" to Out.Key("testController", p(0)),
                $$"$refreshTests" to Out.Key("testController", p(0)),
                $$"$expandTest" to Out.Key("testController", p(0), key = controller),
                $$"$getCodeRelatedToTest" to Out.Key("testController", p(0), key = controller),
            ),
            "ExtHostTerminalService" to mapOf(
                ANY to Out.All,
                // Every host hears of every terminal; one an extension made is its host's from then on.
                $$"$acceptTerminalOpened" to Out.Key("extHostTerminal", p(1), learn = "terminal" to p(0), everywhere = true),
                $$"$startExtensionTerminal" to Out.Key("terminal", p(0)),
                $$"$acceptProcessAckDataEvent" to Out.Key("terminal", p(0)),
                $$"$acceptProcessInput" to Out.Key("terminal", p(0)),
                $$"$acceptProcessResize" to Out.Key("terminal", p(0)),
                $$"$acceptProcessShutdown" to Out.Key("terminal", p(0)),
                $$"$acceptProcessRequestInitialCwd" to Out.Key("terminal", p(0)),
                $$"$acceptProcessRequestCwd" to Out.Key("terminal", p(0)),
                $$"$acceptProcessRequestLatency" to Out.Key("terminal", p(0)),
            ),
            "ExtHostExtensionService" to mapOf(
                $$"$activate" to Out.Extension(0),
                $$"$activateByEvent" to Out.All,
            ),
            // What every host keeps a copy of.
            "ExtHostDocuments" to mapOf(ANY to Out.All),
            "ExtHostDocumentsAndEditors" to mapOf(ANY to Out.All),
            "ExtHostEditors" to mapOf(ANY to Out.All),
            "ExtHostEditorTabs" to mapOf(ANY to Out.All),
            "ExtHostConfiguration" to mapOf(ANY to Out.All),
            "ExtHostWorkspace" to mapOf(ANY to Out.All),
            "ExtHostFileSystemEventService" to mapOf(ANY to Out.All),
            "ExtHostFileSystemInfo" to mapOf(ANY to Out.All),
            "ExtHostDiagnostics" to mapOf(ANY to Out.All),
            "ExtHostOutputService" to mapOf(ANY to Out.All),
            "ExtHostNotebookDocuments" to mapOf(ANY to Out.All),
            "ExtHostNotebookEditors" to mapOf(ANY to Out.All),
            "ExtHostStorage" to mapOf(ANY to Out.All),
            "ExtHostSecretState" to mapOf(ANY to Out.All),
            "ExtHostWindow" to mapOf(ANY to Out.All),
            "ExtHostTheming" to mapOf(ANY to Out.All),
            "ExtHostLabelService" to mapOf(ANY to Out.All),
            "ExtHostLanguages" to mapOf(ANY to Out.All),
        )

        private fun parse(json: String): JsonArray? = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull()

        private fun number(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** A name: a string, or a number a host or the workbench named something by. */
        private fun name(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

        /** An extension identifier, as a string or as the host's `{value, _lower}`. */
        private fun extensionId(value: JsonElement?): String? =
            string(value) ?: string((value as? JsonObject)?.get("value"))

        /** The value at [path] -- a position, then keys -- in [args]. */
        private fun at(args: JsonArray, path: List<Any>): JsonElement? {
            var value: JsonElement? = args
            for (step in path) value = when (step) {
                is Int -> (value as? JsonArray)?.getOrNull(step)
                else -> (value as? JsonObject)?.get(step.toString())
            }
            return value
        }

        /** Replaces the value at [path] with what [change] makes of it, if it makes something. */
        private fun update(args: MutableList<JsonElement>, path: List<Any>, change: (JsonElement) -> JsonElement?): Boolean {
            val position = path.first() as Int
            val current = args.getOrNull(position) ?: return false
            val updated = updateIn(current, path.drop(1), change) ?: return false
            args[position] = updated
            return true
        }

        private fun updateIn(value: JsonElement, path: List<Any>, change: (JsonElement) -> JsonElement?): JsonElement? {
            if (path.isEmpty()) return change(value)
            val step = path.first()
            return when {
                step is Int && value is JsonArray -> value.getOrNull(step)?.let { updateIn(it, path.drop(1), change) }
                    ?.let { JsonArray(value.toMutableList().also { list -> list[step] = it }) }
                value is JsonObject -> value[step.toString()]?.let { updateIn(it, path.drop(1), change) }
                    ?.let { JsonObject(value.toMutableMap().also { map -> map[step.toString()] = it }) }
                else -> null
            }
        }
    }
}

/**
 * A host that takes calls only once [ready] -- its handshake done, which for the web worker host waits
 * on a client to run it -- and until then keeps them, in the order they were made. They fail if it
 * never becomes ready.
 */
internal class StartingHost(private val host: ExtensionHost, private val ready: Deferred<Unit>) : ExtensionHost {
    private val gate = Mutex()

    override val termination: Deferred<Throwable> get() = host.termination

    /** [send], once the host is ready and every call made before it is sent; [send] sends before it first suspends. */
    private suspend fun <T> inTurn(send: suspend () -> T): T = coroutineScope {
        gate.withLock {
            ready.await()
            async(start = CoroutineStart.UNDISPATCHED) { send() }
        }.await()
    }

    override suspend fun call(
        service: String, method: String, argsJson: String, cancellable: Boolean,
        buffers: Map<Int, ByteArray>, objectBuffers: Map<Int, List<ByteArray>>,
    ): String = inTurn { host.call(service, method, argsJson, cancellable, buffers, objectBuffers) }

    override suspend fun callForBytes(service: String, method: String, argsJson: String, cancellable: Boolean): ByteArray? =
        inTurn { host.callForBytes(service, method, argsJson, cancellable) }

    override suspend fun callWithBuffers(
        service: String, method: String, argsJson: String, cancellable: Boolean,
        buffers: Map<Int, ByteArray>, objectBuffers: Map<Int, List<ByteArray>>, timesOut: Boolean,
    ): HostReply = inTurn { host.callWithBuffers(service, method, argsJson, cancellable, buffers, objectBuffers, timesOut) }
}
