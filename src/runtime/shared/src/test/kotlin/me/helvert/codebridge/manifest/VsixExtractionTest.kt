package me.helvert.codebridge.manifest

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.*

class VsixExtractionTest {
    @TempDir lateinit var temporary: Path

    private fun archive(mode: Int, name: String = "extension/adapter", body: String = "binary"): Path {
        val path = temporary.resolve("native.vsix")
        ZipArchiveOutputStream(path).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry(name).apply { unixMode = mode })
            zip.write(body.toByteArray())
            zip.closeArchiveEntry()
        }
        return path
    }

    @Test fun `native executable contents survive extraction`() {
        val target = temporary.resolve("extension")
        VsixArchive.unpack(archive(33261), target) // regular file, 0755
        assertEquals("binary", Files.readString(target.resolve("adapter")))
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            val permissions = Files.getPosixFilePermissions(target.resolve("adapter"))
            assertContains(permissions, PosixFilePermission.OWNER_EXECUTE)
            assertContains(permissions, PosixFilePermission.GROUP_EXECUTE)
            assertContains(permissions, PosixFilePermission.OTHERS_EXECUTE)
        }
    }

    @Test fun `archive symlinks are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VsixArchive.unpack(archive(41471, body = "../../outside"), temporary.resolve("extension"))
        }
    }

    @Test fun `backslash traversal is rejected on every platform`() {
        assertFailsWith<IllegalArgumentException> {
            VsixArchive.unpack(archive(33188, "extension/..\\..\\outside"), temporary.resolve("extension"))
        }
        assertFalse(Files.exists(temporary.parent.resolve("outside")))
    }
}
