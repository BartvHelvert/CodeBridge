package me.helvert.codebridge.status

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.IconUtil
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.helvert.codebridge.icons.ExtensionImages
import java.awt.Image
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * The images in hovers: GitLens starts its blame hover with the author's avatar, a `data:` image or
 * one on the web.
 *
 * Swing's HTML view loads neither -- it knows no `data:` URLs, and would fetch from the web while
 * painting -- so each image is fetched here first, off the UI thread, and kept; the hover's HTML
 * then names it by an address of its own, which the pane looks up in what [resolve] returns.
 */
internal object HoverImages {

    private val images = ConcurrentHashMap<String, Image>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** The images [html] shows, fetched; returns when all are here or have failed. */
    suspend fun fetch(html: String) {
        val wanted = wanted(html)
        if (wanted.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (source in wanted) {
                val image = runCatching { load(source) }
                    .onFailure { LOG.debug("Could not load hover image ${source.take(80)}", it) }
                    .getOrNull()
                if (image != null) images[source] = image else missing += source
            }
        }
    }

    /** The images [html] shows that have not been fetched, nor failed to be. */
    internal fun wanted(html: String): List<String> = sources(html).filter { !images.containsKey(it) && it !in missing }

    /**
     * [html] with each image named by an address of its own, and the images by those addresses,
     * for the pane's image resolver. An image not fetched keeps its address and is not drawn.
     */
    fun resolve(html: String): Pair<String, Dictionary<URL, Image>> {
        val dictionary = Hashtable<URL, Image>()
        val rewritten = SOURCE.replace(html) { match ->
            val image = images[StringUtil.unescapeXmlEntities(match.groupValues[2])] ?: return@replace match.value
            val url = URI("http://codebridge-image.invalid/${dictionary.size}").toURL()
            dictionary[url] = image
            """${match.groupValues[1]}src="$url""""
        }
        return rewritten to dictionary
    }

    private fun sources(html: String) =
        SOURCE.findAll(html).map { StringUtil.unescapeXmlEntities(it.groupValues[2]) }.toSet()

    @Suppress("HttpUrlsUsage") // Recognizes web images; makes none.
    private fun load(source: String): Image? {
        val bytes = when {
            source.startsWith("data:") -> data(source) ?: return null
            source.startsWith("https://") || source.startsWith("http://") ->
                HttpRequests.request(source).connectTimeout(TIMEOUT_MS).readTimeout(TIMEOUT_MS).readBytes(null)

            else -> return null
        }
        if (bytes.size > MAX_BYTES) return null
        if (String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8).contains("<svg")) {
            return ExtensionImages.decode(bytes)?.let { IconUtil.toImage(it) }
        }
        return ImageIO.read(bytes.inputStream())
    }

    /** A `data:` URL's bytes: `data:image/png;base64,...`, or percent-encoded without `;base64`. */
    private fun data(source: String): ByteArray? {
        val comma = source.indexOf(',').takeIf { it > 0 } ?: return null
        val header = source.substring(5, comma)
        if (!header.startsWith("image/")) return null
        val payload = source.substring(comma + 1)
        return if (header.endsWith(";base64")) Base64.getMimeDecoder().decode(payload)
        else URLDecoder.decode(payload.replace("+", "%2B"), Charsets.UTF_8).toByteArray()
    }

    private val SOURCE = Regex("""(<img\b[^>]*?\s)src="([^"]*)"""")
    private const val TIMEOUT_MS = 3_000
    private const val MAX_BYTES = 1 shl 20
    private val LOG = Logger.getInstance(HoverImages::class.java)
}
