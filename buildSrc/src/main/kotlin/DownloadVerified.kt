import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Fetches one file and refuses it unless it has the expected SHA-256. */
@DisableCachingByDefault(because = "A download; keeping a second copy in the build cache gains nothing")
abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val archive: RegularFileProperty

    @TaskAction
    fun download() = downloadVerified(url.get(), sha256.get(), archive.get().asFile)
}

internal data class DownloadPolicy(
    val attempts: Int = 4,
    val backoffMillis: Long = 1_000,
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 120_000,
)

/**
 * Makes [target] the file at [url], unless it already has the SHA-256 [sha256]. Safe for two tasks
 * at once: each fetches to a file of its own and moves it into place whole.
 */
internal fun downloadVerified(url: String, sha256: String, target: File, policy: DownloadPolicy = DownloadPolicy()) {
    // Gradle reruns a download whenever buildSrc changes. Hashing a file already here is far
    // cheaper than fetching it again.
    if (target.isFile && copyHashing(target.inputStream(), OutputStream.nullOutputStream()) == sha256) return
    target.parentFile.mkdirs()
    repeat(policy.attempts) { attempt ->
        // Each attempt owns its partial file; failures never replace a previously verified archive.
        val partial = Files.createTempFile(target.parentFile.toPath(), target.name, ".part")
        try {
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = policy.connectTimeoutMillis
                readTimeout = policy.readTimeoutMillis
            }
            try {
                if (connection is HttpURLConnection) {
                    val status = connection.responseCode
                    if (status !in 200..299) throw HttpDownloadException(url, status)
                }
                val actual = copyHashing(connection.getInputStream(), Files.newOutputStream(partial))
                if (actual != sha256) throw GradleException("Checksum mismatch for $url: expected $sha256 but got $actual")
                Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } finally {
                (connection as? HttpURLConnection)?.disconnect()
            }
        } catch (failure: IOException) {
            if (attempt == policy.attempts - 1 || (failure is HttpDownloadException && !failure.retryable)) throw failure
            System.err.println("Download attempt ${attempt + 1}/${policy.attempts} failed for $url: ${failure.message}; retrying")
        } finally {
            Files.deleteIfExists(partial)
        }
        Thread.sleep(policy.backoffMillis * (1L shl attempt))
    }
}

private class HttpDownloadException(url: String, status: Int) : IOException("HTTP $status downloading $url") {
    val retryable = status == 408 || status == 429 || status in 500..599
}

/** Copies [input] to [output], closing both, and returns the SHA-256 of what passed through. */
private fun copyHashing(input: InputStream, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.use { source -> DigestOutputStream(output, digest).use { source.copyTo(it) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
