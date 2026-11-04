package me.helvert.codebridge.webview

import me.helvert.codebridge.rpc.WebviewResourceDto

/**
 * The files webview pages read from the backend, kept so a page loaded again -- a custom editor
 * opened once more, a panel whose extension set its HTML anew -- is not sent them again: draw.io's
 * page alone reads 17 MB of scripts.
 *
 * Every read still asks the backend, which checks the address against the webview's roots and
 * answers without the bytes when the copy here is of the file as it is now. The copies kept come to
 * at most [capacity] bytes, those read longest ago going first.
 */
internal class WebviewResources(private val capacity: Long) {

    /** A file a page read: its bytes and media type, and the version of the file they are. */
    class Resource(val bytes: ByteArray, val mimeType: String, val version: String)

    private val kept = LinkedHashMap<String, Resource>(16, 0.75f, true)
    private var size = 0L

    /**
     * The file at [url], asking [fetch] with the version of the copy kept, if any; null when the
     * backend refuses it, which also forgets the copy.
     */
    suspend fun read(url: String, fetch: suspend (cachedVersion: String?) -> WebviewResourceDto?): Resource? {
        val copy = synchronized(this) { kept[url] }
        val answer = fetch(copy?.version)
        val bytes = answer?.bytes
        return when {
            answer == null -> null.also { forget(url) }
            bytes == null -> copy?.takeIf { it.version == answer.version }
                // The copy went while the backend was asked: the file is asked for again, in full.
                ?: fetch(null)?.let { full -> full.bytes?.let { keep(url, Resource(it, full.mimeType, full.version)) } }
            else -> keep(url, Resource(bytes, answer.mimeType, answer.version))
        }
    }

    @Synchronized
    private fun keep(url: String, resource: Resource): Resource {
        kept.remove(url)?.let { size -= it.bytes.size }
        if (resource.bytes.size > capacity) return resource
        kept[url] = resource
        size += resource.bytes.size
        val oldest = kept.entries.iterator()
        while (size > capacity && oldest.hasNext()) {
            size -= oldest.next().value.bytes.size
            oldest.remove()
        }
        return resource
    }

    @Synchronized
    private fun forget(url: String) {
        kept.remove(url)?.let { size -= it.bytes.size }
    }

    /** How many bytes the copies kept come to. */
    @get:Synchronized
    val keptBytes: Long get() = size
}
