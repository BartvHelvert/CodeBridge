package me.helvert.codebridge.uitest

import com.intellij.ide.starter.community.PublicIdeDownloader
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.installer.IdeInstallerFile
import com.intellij.ide.starter.models.IdeInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * Starter looks up release metadata even when its installer is already on disk. Our UI tests pin
 * the build, so that lookup adds a network dependency to every test without changing the archive.
 * Keep Starter's archive name (including its platform/architecture suffix) and extraction logic.
 */
internal class CachedIdeDownloader(private val publicDownloader: IdeDownloader = PublicIdeDownloader()) : IdeDownloader {
    override fun downloadIdeInstaller(ideInfo: IdeInfo, installerDirectory: Path): IdeInstallerFile {
        val build = ideInfo.buildNumber
        // An unpinned or mutable build still needs the catalogue to resolve its current archive.
        if (build.isNotBlank() && build != "SNAPSHOT") {
            val archive = installerDirectory.resolve("${ideInfo.installerFilePrefix}-$build${ideInfo.installerFileExt}")
            if (Files.isRegularFile(archive) && Files.size(archive) > 0) {
                println("Reusing cached UI-test installer: ${archive.fileName}")
                return IdeInstallerFile(archive, build)
            }
            // Starter only checks existence; an empty file must not suppress the real download.
            if (Files.isRegularFile(archive) && Files.size(archive) == 0L) Files.delete(archive)
        }
        return publicDownloader.downloadIdeInstaller(ideInfo, installerDirectory)
    }
}
