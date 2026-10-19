package me.helvert.codebridge.language

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/**
 * The image an extension gave one of its languages, where it gave one.
 *
 * VS Code says it in the manifest -- `contributes.languages[].icon` names a light and a dark
 * variant -- and that field means there what it means here: the icon a file of that language gets
 * in the tree, where the icon theme has none for the language. Most extensions declare none, and
 * answering null for those is the whole of this class's opinion on the matter; [CodeBridgeMonogram] draws
 * what they get instead.
 *
 * [light] and [dark] are paths already resolved and checked to be inside the extension directory;
 * they arrive from the manifest and are otherwise untrusted.
 */
internal class CodeBridgeLanguageIcon(private val light: Path?, private val dark: Path?) {

    /**
     * Loaded once each, on first paint, and kept.
     *
     * Reading and decoding an image is not something to do per repaint of a file tree, and the
     * platform asks a file type for its icon exactly that often. A failure caches too: a file that
     * could not be read once will not read next frame either, and the log line would repeat forever.
     */
    private val lightIcon: Icon? by lazy { load(light) }
    private val darkIcon: Icon? by lazy { load(dark) }

    /**
     * The image for the theme in use, or null when the extension offered none this IDE can read.
     *
     * The theme is read per call rather than baked in, because it changes while the IDE is running
     * and the file type instance has to outlive that -- the platform compares file types by
     * identity, so replacing one to repaint an icon would read as every open file changing type.
     */
    fun current(): Icon? {
        if (light == null && dark == null) return null
        val dark = !JBColor.isBright()
        return (if (dark) darkIcon else lightIcon) ?: (if (dark) lightIcon else darkIcon)
    }

    private fun load(path: Path?): Icon? {
        if (path == null) return null
        return runCatching {
            val size = Files.size(path)
            require(size in 1..MAX_BYTES) { "$path is $size bytes; an icon may be at most $MAX_BYTES" }
            val loaded = IconLoader.findIcon(path.toUri().toURL(), true)
                ?: error("$path is not an image this IDE can read")
            // Whatever the extension shipped, scaled to what a file tree row expects. Extensions
            // publish these at whatever size suits their marketplace page -- rust-analyzer's is
            // 600px -- and a row that size is worse than no icon at all.
            //
            // `IconUtil.scale` and not `IconUtil.resizeSquared`: the latter clamps its scale factor
            // to a tenth, so it reported 16 and painted 60 straight over the file name beside it.
            // This one rescales the image itself for a `CachedImageIcon`, which is what
            // `IconLoader` returns, so what it reports is what it paints.
            val width = loaded.iconWidth
            require(width > 0) { "$path loaded with no width" }
            if (width == SIZE) loaded else IconUtil.scale(loaded, null, SIZE.toFloat() / width)
        }.onFailure { LOG.warn("Could not read an icon an extension gave one of its languages", it) }
            .getOrNull()
    }

    companion object {
        /** For a language whose extension offered no image at all. */
        val NONE = CodeBridgeLanguageIcon(null, null)

        private val LOG = Logger.getInstance(CodeBridgeLanguageIcon::class.java)

        /** One file tree row, in unscaled pixels; the platform scales from there. */
        private const val SIZE = 16

        /** Generous for an icon and far short of anything that would hurt to decode. */
        private const val MAX_BYTES = 2L * 1024 * 1024
    }
}
