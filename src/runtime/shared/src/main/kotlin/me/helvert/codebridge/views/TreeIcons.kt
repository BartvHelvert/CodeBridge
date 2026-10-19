package me.helvert.codebridge.views

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.icons.icon
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.icons.ExtensionImages
import me.helvert.codebridge.icons.FileIcons
import me.helvert.codebridge.rpc.TreeItemDto
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import kotlin.math.roundToInt

/**
 * The icons of tree views and the tool windows holding them.
 *
 * An extension names a codicon, `$(name)`, or an image file of its own, which is on the backend and
 * arrives here as bytes by the key the backend gave it. Those are fetched once and drawn at the
 * IDE's icon size; until one arrives the item has no icon and is repainted when it lands.
 */
object TreeIcons {

    /** A tool window's icon until its own arrives, and for a container that named none. */
    val placeholder: Icon = scalable(Codicons.icon("list-tree"))

    private val images = ConcurrentHashMap<Pair<TreeViewSource, String>, Icon>()
    private val pending = ConcurrentHashMap.newKeySet<Pair<TreeViewSource, String>>()

    /** The icon for [item]; [loaded] is called once an image this had to fetch is here. */
    fun item(source: TreeViewSource, scope: CoroutineScope, item: TreeItemDto, loaded: () -> Unit): Icon? {
        icon(source, scope, item.icon, item.iconDark, loaded)?.let { return it }
        val name = item.resourceName
            ?: item.resourcePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
        // VS Code draws a resource's icon from its icon theme; the IDE's own file icons are that here.
        item.fileIcon?.let { return it.icon() }
        return if (item.folder) AllIcons.Nodes.Folder
        else FileIcons.forFile(name) ?: FileTypeManager.getInstance().getFileTypeByFileName(name).icon
    }

    /**
     * The icon [light] names, or [dark] under a dark theme when there is one; null while an image is
     * still being fetched, and [loaded] is called once it is here.
     */
    fun icon(source: TreeViewSource, scope: CoroutineScope, light: String?, dark: String?, loaded: () -> Unit): Icon? {
        val spec = (if (!JBColor.isBright()) dark else null) ?: light ?: return null
        return cached(source, scope, spec, loaded)
    }

    /**
     * Calls [apply] with the tool window icon [spec] names, now for a codicon and once fetched for
     * an image. Always a [ScalableIcon], which a stripe button in the new UI requires.
     *
     * An image is drawn as VS Code draws an activity bar icon: as a mask, its shape in the colour of
     * the text beside it, whatever colours the image has -- an extension ships one image for every
     * theme, often dark, which drawn as it is would barely show on a dark one.
     */
    fun load(source: TreeViewSource, scope: CoroutineScope, spec: String?, apply: (Icon) -> Unit) {
        if (spec == null) return
        codicon(spec)?.let { apply(scalable(it)); return }
        scope.launch {
            val icon = fetch(source, spec) ?: return@launch
            withContext(Dispatchers.EDT) { apply(Mask(scalable(icon))) }
        }
    }

    private fun scalable(icon: Icon): ScalableIcon = icon as? ScalableIcon ?: Scaled(icon)

    /** [icon]'s shape filled with the foreground of what it is painted on, as a codicon is. */
    private class Mask(private val icon: ScalableIcon) : ScalableIcon {
        override fun getIconWidth(): Int = icon.iconWidth
        override fun getIconHeight(): Int = icon.iconHeight
        override fun getScale(): Float = icon.scale
        override fun scale(scaleFactor: Float): Icon =
            if (scaleFactor == scale) this else Mask(scalable(icon.scale(scaleFactor)))

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            if (iconWidth <= 0 || iconHeight <= 0) return
            val image = ImageUtil.createImage(g, iconWidth, iconHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                icon.paintIcon(c, graphics, 0, 0)
                graphics.composite = AlphaComposite.SrcIn
                graphics.color = c?.foreground ?: UIUtil.getLabelForeground()
                graphics.fillRect(0, 0, iconWidth, iconHeight)
            } finally {
                graphics.dispose()
            }
            UIUtil.drawImage(g, image, x, y, null)
        }
    }

    /** [icon] drawn at [scale], for an icon that cannot scale itself. */
    private class Scaled(private val icon: Icon, private val scale: Float = 1f) : ScalableIcon {
        override fun getIconWidth(): Int = (icon.iconWidth * scale).roundToInt()
        override fun getIconHeight(): Int = (icon.iconHeight * scale).roundToInt()
        override fun getScale(): Float = scale
        override fun scale(scaleFactor: Float): Icon = if (scaleFactor == scale) this else Scaled(icon, scaleFactor)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            if (scale == 1f) return icon.paintIcon(c, g, x, y)
            IconUtil.scale(icon, c, scale).paintIcon(c, g, x, y)
        }
    }

    private fun cached(source: TreeViewSource, scope: CoroutineScope, spec: String, loaded: () -> Unit): Icon? {
        codicon(spec)?.let { return it }
        val key = source to spec
        images[key]?.let { return it }
        if (pending.add(key)) scope.launch {
            if (fetch(source, spec) != null) withContext(Dispatchers.EDT) { loaded() }
        }
        return null
    }

    private fun codicon(spec: String): Icon? =
        if (spec.startsWith("$(")) Codicons.references(spec).firstOrNull()?.let(Codicons::icon) else null

    private suspend fun fetch(source: TreeViewSource, key: String): Icon? {
        val cacheKey = source to key
        images[cacheKey]?.let { return it }
        val bytes = try {
            source.icon(key)
        } catch (cancelled: CancellationException) {
            pending.remove(cacheKey)
            throw cancelled
        } catch (error: Exception) {
            LOG.debug("Could not fetch tree view icon $key", error)
            null
        } ?: return null
        val icon = runCatching { ExtensionImages.decode(bytes) }.onFailure {
            LOG.debug(
                "Could not read tree view icon $key",
                it
            )
        }.getOrNull()
            ?: return null
        images[cacheKey] = icon
        return icon
    }

    private val LOG = Logger.getInstance(TreeIcons::class.java)
}
