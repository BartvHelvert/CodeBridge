import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

/**
 * Makes [target] the file at [url], unless it already has the SHA-256 [sha256]. Safe for two tasks
 * at once: each fetches to a file of its own and moves it into place whole.
 */
fun downloadVerified(url: String, sha256: String, target: File) {
    // Gradle reruns a download whenever buildSrc changes. Hashing a file already here is far
    // cheaper than fetching it again.
    if (target.isFile && copyHashing(target.inputStream(), OutputStream.nullOutputStream()) == sha256) return
    target.parentFile.mkdirs()
    val partial = Files.createTempFile(target.parentFile.toPath(), target.name, ".part")
    try {
        val actual = copyHashing(URI(url).toURL().openStream(), Files.newOutputStream(partial))
        if (actual != sha256) throw GradleException("Checksum mismatch for $url: expected $sha256 but got $actual")
        Files.move(partial, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(partial)
    }
}

/** Copies [input] to [output], closing both, and returns the SHA-256 of what passed through. */
private fun copyHashing(input: InputStream, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.use { source -> DigestOutputStream(output, digest).use { source.copyTo(it) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
