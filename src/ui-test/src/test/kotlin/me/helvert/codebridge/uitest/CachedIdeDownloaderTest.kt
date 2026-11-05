package me.helvert.codebridge.uitest

import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.installer.IdeInstallerFile
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimateProductInit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class CachedIdeDownloaderTest {
    @TempDir
    lateinit var directory: Path

    private val pinned = IdeaUltimateProductInit().ideInfo.copy(buildNumber = "263.7307.44")

    private val unavailable = object : IdeDownloader {
        override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile =
            throw UnknownHostException("Release catalogue unavailable")
    }

    @Test
    fun `a downloaded installer is reused by a new downloader while the catalogue is unavailable`() {
        var downloads = 0
        val initial = CachedIdeDownloader(object : IdeDownloader {
            override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile {
                downloads++
                val archive = archive(ideInfo)
                archive.writeText("installer")
                return IdeInstallerFile(archive, ideInfo.buildNumber)
            }
        }).downloadIdeInstaller(pinned, directory)

        val reused = CachedIdeDownloader(unavailable).downloadIdeInstaller(pinned, directory)
        assertEquals(initial.installerFile, reused.installerFile)
        assertEquals(pinned.buildNumber, reused.buildNumber)
        assertEquals(1, downloads)
    }

    @Test
    fun `another build's cached installer cannot satisfy the pinned build`() {
        archive(pinned.copy(buildNumber = "263.7031.23")).writeText("older installer")
        assertFailsWith<UnknownHostException> {
            CachedIdeDownloader(unavailable).downloadIdeInstaller(pinned, directory)
        }
    }

    @Test
    fun `an empty archive is removed before the public downloader checks existence`() {
        val empty = Files.createFile(archive(pinned))
        val downloader = CachedIdeDownloader(object : IdeDownloader {
            override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile {
                assertFalse(Files.exists(empty), "Starter must not mistake an empty file for a cached download")
                empty.writeText("downloaded installer")
                return IdeInstallerFile(empty, ideInfo.buildNumber)
            }
        })
        assertEquals(empty, downloader.downloadIdeInstaller(pinned, directory).installerFile)
    }

    @Test
    fun `unpinned and snapshot builds still resolve through the public downloader`() {
        for (build in listOf("", "SNAPSHOT")) {
            val info = pinned.copy(buildNumber = build)
            archive(info).writeText("stale installer")
            assertFailsWith<UnknownHostException> {
                CachedIdeDownloader(unavailable).downloadIdeInstaller(info, directory)
            }
        }
    }

    private fun archive(info: IdeInfo): Path =
        directory.resolve("${info.installerFilePrefix}-${info.buildNumber}${info.installerFileExt}")
}
