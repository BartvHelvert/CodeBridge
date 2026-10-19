package me.helvert.codebridge.registry

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.io.HttpRequests
import me.helvert.codebridge.extensions.ExtensionStore
import me.helvert.codebridge.rpc.OperationProgress
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Talks to Open VSX.
 *
 * Open VSX rather than Microsoft's Marketplace: the Marketplace terms of use restrict its service
 * to Microsoft's own products, so a third-party client has no business there. Open VSX is an
 * Eclipse Foundation registry built for exactly this.
 *
 * Requests go through [HttpRequests] so they honour the IDE's proxy and certificate settings; a
 * plain `URL.openStream` would work at a desk and fail behind a corporate proxy.
 */
@Service(Service.Level.APP)
class OpenVsxRegistry {


    /** One page of results. [query] may be blank, which lists popular extensions. */
    @Throws(IOException::class)
    fun search(query: String, offset: Int = 0, size: Int = PAGE_SIZE): RegistrySearch {
        val url = buildString {
            append(BASE).append("/-/search?size=").append(size).append("&offset=").append(offset)
            if (query.isNotBlank()) append("&query=").append(encode(query))
        }
        return OpenVsxJson.parseSearch(get(url))
    }

    /** Everything known about one extension, including where to download it. */
    @Throws(IOException::class)
    fun detail(namespace: String, name: String): RegistryDetail =
        OpenVsxJson.parseDetail(get("$BASE/${encode(namespace)}/${encode(name)}"))

    /**
     * Downloads the VSIX for [targetPlatform] into [directory] and verifies it.
     *
     * The checksum is not optional: this writes a file that will later be unpacked and executed,
     * and the download crosses whatever proxies sit between here and the registry.
     */
    @Throws(IOException::class)
    fun download(
        detail: RegistryDetail,
        targetPlatform: String,
        directory: Path,
        progress: OperationProgress,
    ): Path {
        val source = detail.downloadFor(targetPlatform) ?: throw IOException(
            "${detail.extension.id} publishes no package for $targetPlatform" +
                detail.platforms().takeIf { it.isNotEmpty() }?.let { " (only ${it.joinToString()})" }.orEmpty()
        )

        Files.createDirectories(directory)
        val file = directory.resolve("${detail.extension.id}-${detail.extension.version}.vsix")

        progress.text = "Downloading ${detail.extension.label}"
        HttpRequests.request(source).productNameAsUserAgent().connect { request ->
            val size = request.connection.contentLengthLong
            request.inputStream.use { input ->
                Files.newOutputStream(file).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var copied = 0L
                    while (true) {
                        progress.checkCanceled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (size > 0) progress.fraction = copied.toDouble() / size
                    }
                }
            }
        }

        progress.checkCanceled()
        RegistryDetail.checksumUrl(source)?.let { checksum ->
            progress.text = "Verifying ${detail.extension.label}"
            progress.fraction = null
            verify(file, checksum)
        }
        return file
    }

    private fun verify(file: Path, checksumUrl: String) {
        val published = runCatching {
            HttpRequests.request(checksumUrl).productNameAsUserAgent().readString(null)
        }.getOrElse {
            // A missing checksum is not a reason to install something unverified.
            Files.deleteIfExists(file)
            throw IOException("Could not fetch the checksum for this extension: ${it.message}")
        }.trim().substringBefore(' ').lowercase()

        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != published) {
            Files.deleteIfExists(file)
            throw IOException("Checksum mismatch: expected $published but got $actual.")
        }
    }

    private fun get(url: String): String =
        HttpRequests.request(url)
            .productNameAsUserAgent()
            .connectTimeout(15_000)
            .readTimeout(30_000)
            .readString(null)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        fun getInstance(): OpenVsxRegistry = service()

        /** The Eclipse Foundation registry's API, on the same host as the pages it serves. */
        const val BASE: String = "$OPEN_VSX/api"

        const val PAGE_SIZE: Int = 30

        /** The platform token to install for, matching the keys Open VSX publishes under. */
        fun targetPlatform(): String = ExtensionStore.currentTargetPlatform()
    }
}
