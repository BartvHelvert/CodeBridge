package me.helvert.codebridge.icons

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.scale.ScaleContext
import com.intellij.ui.scale.ScaleType
import com.intellij.util.IconUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import java.awt.Image
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.math.roundToInt

/**
 * Image files of an extension's own, as icons: tree items and view containers, and decorations'
 * gutter icons. They are on the backend and reach the frontend as bytes.
 */
object ExtensionImages {

    private val svgFiles = ConcurrentHashMap<String, Path>()

    /** An SVG or raster image, drawn at the IDE's icon size for the screen's scale; null when unreadable. */
    fun decode(bytes: ByteArray): Icon? {
        val context = ScaleContext.create()
        if (String(bytes, 0, minOf(bytes.size, 1024), Charsets.UTF_8).contains("<svg")) {
            // IconLoader keeps SVGs scalable, rendering at the requested size and screen scale.
            // Content-addressed files let its public URL API read bytes fetched from the backend.
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = svgFiles.computeIfAbsent(hash) {
                val directory = PathManager.getSystemDir().resolve("codebridge").resolve("tree-icons")
                Files.createDirectories(directory)
                val target = directory.resolve("$hash.svg")
                if (!Files.exists(target)) Files.write(target, bytes)
                target
            }
            val icon = IconLoader.findIcon(file.toUri().toURL()) ?: return null
            val largest = maxOf(icon.iconWidth, icon.iconHeight)
            if (largest <= 0) return null
            val size = JBUI.scale(SIZE)
            return IconUtil.toSize(IconUtil.scale(icon, null, size.toFloat() / largest), size, size)
        }
        val source: Image = ImageIO.read(bytes.inputStream()) ?: return null
        val system = context.getScale(ScaleType.SYS_SCALE)
        val device = (JBUI.scale(SIZE) * system).roundToInt()
        val width = source.getWidth(null).coerceAtLeast(1)
        val height = source.getHeight(null).coerceAtLeast(1)
        val factor = minOf(device.toDouble() / width, device.toDouble() / height)
        val scaledWidth = (width * factor).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * factor).roundToInt().coerceAtLeast(1)
        val raster = ImageUtil.scaleImage(source, scaledWidth, scaledHeight)
        return JBImageIcon(ImageUtil.ensureHiDPI(raster, context, scaledWidth / system, scaledHeight / system))
    }

    private const val SIZE = 16
}
