package me.helvert.codebridge.workbench

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Decorations
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import org.jetbrains.annotations.TestOnly
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `MainThreadDecorations`: what `FileDecorationProvider`s say about files, for the Project view.
 *
 * VS Code asks the providers about each resource its explorer shows, and the IDE asks a
 * [ProjectViewNodeDecorator] about each node it draws -- synchronously, which an extension cannot
 * answer. So a node is drawn with what is known, a file not asked about yet is asked, in a batch
 * with the others drawn alongside it, and the view is redrawn once an answer decorates anything.
 * `$onDidChange` forgets what the provider said about the resources it names, which asks again
 * when they are next drawn.
 */
@Service(Service.Level.PROJECT)
class HostedDecorations(private val project: Project) {

    /** A file's decoration, from every provider at once. */
    data class Shown(val badge: String?, val tooltip: String?, val color: Color?)

    private val providers = ConcurrentHashMap<Int, String>()
    /** What each provider said about each resource asked about, by [UriComponents.key]; empty for nothing. */
    private val known = ConcurrentHashMap<String, Map<Int, Decorations.Decoration>>()
    private val asking = ConcurrentHashMap<String, UriComponents>()
    private val flushing = AtomicBoolean()
    /** Batches asked about and not yet answered. */
    private val answering = AtomicInteger()
    private val redrawing = AtomicBoolean()
    @Volatile private var callHost: (suspend (String, String, String) -> String)? = null

    fun hostStarted(call: suspend (service: String, method: String, args: String) -> String) {
        callHost = call
    }

    fun hostStopped() {
        callHost = null
        providers.clear()
        asking.clear()
        if (known.isNotEmpty()) { known.clear(); redraw() }
    }

    /** Handles one `MainThreadDecorations` call. Nothing here returns a value to the host. */
    fun handle(call: MainThreadCall) {
        try {
            when (call.method) {
                Decorations.REGISTER -> {
                    val (handle, extension) = Decorations.registration(call.argsJson)
                    providers[handle] = extension.orEmpty()
                    // Asked about everything again, so the new provider is asked too.
                    forget(null)
                }
                Decorations.UNREGISTER -> {
                    val handle = Decorations.unregistered(call.argsJson)
                    providers.remove(handle)
                    known.replaceAll { _, said -> said - handle }
                    redraw()
                }
                Decorations.CHANGED -> forget(Decorations.changed(call.argsJson).second)
            }
        } catch (failure: Exception) {
            LOG.warn("Could not handle ${call.service}.${call.method}", failure)
        }
    }

    /**
     * [file]'s decoration as known now, asking about it if it has not been. A folder takes the
     * colour of a file under it whose provider propagates it, with a dot for a badge, as VS Code
     * draws a folder holding a modified file.
     */
    fun decoration(file: VirtualFile): Shown? {
        if (providers.isEmpty()) return null
        val path = runCatching { file.toNioPath() }.getOrNull() ?: return null
        val resource = UriComponents.of(path)
        val key = UriComponents.key(resource.uri)
        val said = known[key]
        if (said == null) ask(key, resource)
        val own = said.orEmpty().values.filter { it.badge != null || it.color != null || it.tooltip != null }
        if (own.isNotEmpty()) return Shown(
            badge = own.mapNotNull { it.badge }.distinct().joinToString(",").ifEmpty { null },
            tooltip = own.mapNotNull { it.tooltip }.distinct().joinToString(", ").ifEmpty { null },
            color = own.firstNotNullOfOrNull { it.color }?.let(::color),
        )
        if (!file.isDirectory) return null
        val prefix = key.trimEnd('/') + "/"
        val below = known.entries.firstNotNullOfOrNull { (other, decorations) ->
            if (!other.startsWith(prefix)) null else decorations.values.firstOrNull { it.propagate && it.color != null }
        } ?: return null
        return Shown(badge = "•", tooltip = null, color = below.color?.let(::color))
    }

    private fun color(id: String): Color? = ThemeColors.installed().resolve(id)

    /** Forgets what was said about [resources], or about everything, so it is asked again. */
    private fun forget(resources: List<UriComponents>?) {
        if (resources == null) known.clear()
        else resources.forEach { known.remove(UriComponents.key(it.uri)) }
        redraw()
    }

    private fun ask(key: String, resource: UriComponents) {
        if (asking.putIfAbsent(key, resource) != null) return
        // Batched: a view draws its nodes one after another, and asks about each.
        if (flushing.compareAndSet(false, true)) Scope.getInstance(project).scope.launch(Dispatchers.IO) {
            delay(BATCH_MILLIS.milliseconds)
            flush()
        }
    }

    private suspend fun flush() {
        flushing.set(false)
        answering.incrementAndGet()
        try {
            val batch = asking.keys.toList().mapNotNull { key -> asking.remove(key)?.let { key to it } }
            ask(batch)
        } finally {
            answering.decrementAndGet()
        }
    }

    private suspend fun ask(batch: List<Pair<String, UriComponents>>) {
        val call = callHost
        if (batch.isEmpty() || call == null) return
        val handles = providers.keys.toList()
        val resources = batch.map { it.second }
        // Every provider at once; one that fails or is slow says nothing about this batch.
        val replies = coroutineScope {
            handles.map { handle ->
                async {
                    try {
                        withTimeoutOrNull(TIMEOUT_SECONDS.seconds) {
                            Decorations.decorations(call(Decorations.EXT_HOST, Decorations.PROVIDE, Decorations.provide(handle, resources)))
                        }.also { if (it == null) LOG.debug("Decoration provider $handle did not answer in time") }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        LOG.debug("Decoration provider $handle failed", failure)
                        null
                    }
                }
            }.awaitAll()
        }
        var decorated = false
        batch.forEachIndexed { index, (key, _) ->
            val said = handles.zip(replies).mapNotNull { (handle, reply) -> reply?.get(index)?.let { handle to it } }.toMap()
            known[key] = said
            if (said.isNotEmpty()) decorated = true
        }
        if (decorated) redraw()
    }

    /** Redraws the Project view once, however many answers asked for it meanwhile. */
    private fun redraw() {
        if (!redrawing.compareAndSet(false, true)) return
        Scope.getInstance(project).scope.launch(Dispatchers.EDT) {
            redrawing.set(false)
            ProjectView.getInstance(project).refresh()
        }
    }

    @TestOnly
    fun awaitForTest(timeoutMillis: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (asking.isEmpty() && !flushing.get() && answering.get() == 0) return true
            Thread.sleep(5)
        }
        return false
    }

    /** Where batches are asked about and the view redrawn: no longer than the project. */
    @Service(Service.Level.PROJECT)
    private class Scope(val scope: CoroutineScope) {
        companion object {
            fun getInstance(project: Project): Scope = project.service()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(HostedDecorations::class.java)
        private const val BATCH_MILLIS = 50L
        private const val TIMEOUT_SECONDS = 10L

        fun getInstance(project: Project): HostedDecorations = project.service()
    }
}

/**
 * Draws [HostedDecorations] in the Project view: the name in the decoration's colour, its badge
 * beside it, and its tooltip on hover.
 */
internal class CodeBridgeDecorationsNodeDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val file = node.virtualFile ?: return
        val shown = project.serviceIfCreated<HostedDecorations>()?.decoration(file) ?: return
        shown.color?.let { data.forcedTextForeground = it }
        shown.badge?.let { badge ->
            data.locationString = listOfNotNull(data.locationString?.takeIf { it.isNotBlank() }, badge).joinToString("  ")
        }
        if (shown.tooltip != null && data.tooltip == null) data.tooltip = shown.tooltip
    }
}
