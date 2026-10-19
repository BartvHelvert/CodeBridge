package me.helvert.codebridge.icons

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.icons.IconReplacer
import com.intellij.ui.icons.ReplaceableIcon
import com.intellij.ui.scale.JBUIScale
import me.helvert.codebridge.icons.ContributedIcons.SIZE
import me.helvert.codebridge.rpc.ContributedIconsDto
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.nio.file.Files
import java.security.MessageDigest
import java.util.*
import javax.swing.Icon
import kotlin.math.roundToInt

/**
 * The icons extensions contribute (`contributes.icons`), named as codicons are: `$(gitlens-graph)`.
 *
 * Each is a glyph of an icon font the extension ships, or another icon by name. A glyph is drawn as
 * VS Code draws a codicon -- the font at the icon's size, in the colour of the IDE's icons -- and kept
 * as an SVG of its outline, so it is an ordinary icon to the IDE: it scales, follows the theme and
 * reaches a client the way the bundled codicons do.
 *
 * They are known only once the enabled extensions' manifests have been read, which on a client is a
 * request to the backend. A name asked for before then is answered with an icon that looks itself up
 * whenever it is painted: [Codicons.DEFAULT] until the extension's icon is here, and that once it is.
 */
object ContributedIcons {

    @Volatile
    private var icons: Map<String, Icon> = emptyMap()

    @Volatile
    private var aliases: Map<String, String> = emptyMap()

    private var last: ContributedIconsDto? = null

    /** Replaces what the extensions contribute with [contributed]; whether anything changed. */
    @Synchronized
    fun replace(contributed: ContributedIconsDto): Boolean {
        if (contributed == last) return false
        last = contributed
        val glyphs = HashMap<String, Icon>()
        for ((bytes, characters) in contributed.fonts) {
            val loaded = IconFonts.load(bytes)
            if (loaded == null) {
                LOG.info("Could not read the icon font of ${characters.keys.sorted().joinToString()}")
                continue
            }
            for ((id, character) in characters) {
                runCatching { glyph(loaded, character) }
                    .onFailure { LOG.info("Could not draw icon `$id`", it) }
                    .getOrNull()?.let { glyphs[id] = it }
            }
        }
        icons = glyphs
        aliases = contributed.aliases
        return true
    }

    /** The icon contributed as [id], when an enabled extension contributes one now. */
    fun find(id: String): Icon? = find(id, 0)

    private fun find(id: String, depth: Int): Icon? {
        icons[id]?.let { return it }
        val alias = aliases[id] ?: return null
        // Contributions name each other; a cycle among them names nothing.
        if (depth > 8) return null
        return Codicons.known(alias) ?: Codicons.bundled(alias) ?: find(alias, depth + 1)
    }

    /** The icon for [id]: the extension's, or one that draws it once it is here; see [ContributedIcons]. */
    fun icon(id: String): Icon = find(id) ?: Contributed(id)

    /**
     * Whatever [id] is when painted, at the size of a codicon.
     *
     * The IDE recolours icons by replacing what is inside them -- a selected tool window button
     * draws its icon in the selection's foreground (`toStrokeIcon`) -- and can only reach inside an
     * icon that lets it, so this keeps each [replacer] it is given and applies it to what it draws.
     */
    class Contributed(
        val id: String,
        private val scale: Float = 1f,
        private val replacer: IconReplacer? = null,
    ) : ScalableIcon, ReplaceableIcon {
        /** What this draws now. */
        val current: Icon get() = find(id) ?: Codicons.DEFAULT

        /** [current] as last replaced, so the replacement is made once rather than on every paint. */
        @Volatile
        private var replaced: Pair<Icon, Icon>? = null

        override fun getIconWidth(): Int = JBUIScale.scale(SIZE * scale).roundToInt()
        override fun getIconHeight(): Int = iconWidth
        override fun getScale(): Float = scale
        override fun scale(scaleFactor: Float): Icon =
            if (scaleFactor == scale) this else Contributed(id, scaleFactor, replacer)

        override fun replaceBy(replacer: IconReplacer): Icon {
            val previous = this.replacer ?: return Contributed(id, scale, replacer)
            return Contributed(id, scale, object : IconReplacer {
                override fun replaceIcon(icon: Icon?): Icon? = replacer.replaceIcon(previous.replaceIcon(icon))
            })
        }

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            var icon = drawn()
            if (icon.iconWidth != iconWidth && icon is ScalableIcon && icon.iconWidth > 0) {
                icon = icon.scale(iconWidth.toFloat() / icon.iconWidth * icon.scale)
            }
            icon.paintIcon(c, g, x + (iconWidth - icon.iconWidth) / 2, y + (iconHeight - icon.iconHeight) / 2)
        }

        private fun drawn(): Icon {
            val icon = current
            val replacer = replacer ?: return icon
            replaced?.let { (source, result) -> if (source === icon) return result }
            return replacer.replaceIcon(icon).also { replaced = icon to it }
        }

        override fun equals(other: Any?) =
            other is Contributed && other.id == id && other.scale == scale && other.replacer == replacer

        override fun hashCode() = (id.hashCode() * 31 + scale.hashCode()) * 31 + replacer.hashCode()
    }

    /** Glyph [character] of [font] as an icon, from an SVG of its outline in each theme's icon colour. */
    private fun glyph(font: Font, character: Int): Icon? {
        if (!font.canDisplay(character)) return null
        val path = pathAttributes(outline(font, character))
        val light = svg(path, LIGHT)
        val hash =
            MessageDigest.getInstance("SHA-256").digest(light.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = PathManager.getSystemDir().resolve("codebridge").resolve("contributed-icons")
        Files.createDirectories(directory)
        // IconLoader takes `name_dark.svg` beside `name.svg` under a dark theme, as for the bundled codicons.
        val file = directory.resolve("$hash.svg")
        if (!Files.exists(file)) Files.writeString(file, light)
        val dark = directory.resolve("${hash}_dark.svg")
        if (!Files.exists(dark)) Files.writeString(dark, svg(path, DARK))
        return IconLoader.findIcon(file.toUri().toURL())
    }

    /**
     * The glyph's visible outline centred in a box of [SIZE]. Icon fonts' advances and baseline
     * metrics may leave unequal padding, which would offset the shape inside a tool window button.
     */
    internal fun outline(font: Font, character: Int): Shape {
        val sized = font.deriveFont(SIZE.toFloat())
        val text = String(Character.toChars(character))
        val context = FontRenderContext(null, true, true)
        val glyphs = sized.createGlyphVector(context, text)
        val shape = glyphs.outline
        val bounds = shape.bounds2D
        return AffineTransform.getTranslateInstance(SIZE / 2.0 - bounds.centerX, SIZE / 2.0 - bounds.centerY)
            .createTransformedShape(shape)
    }

    internal fun pathAttributes(shape: Shape): String {
        val data = StringBuilder()
        val iterator = shape.getPathIterator(null)
        val coords = FloatArray(6)
        while (!iterator.isDone) {
            val (command, points) = when (iterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> "M" to 1
                PathIterator.SEG_LINETO -> "L" to 1
                PathIterator.SEG_QUADTO -> "Q" to 2
                PathIterator.SEG_CUBICTO -> "C" to 3
                else -> "Z" to 0
            }
            data.append(command)
            for (i in 0 until points * 2) {
                if (i > 0) data.append(' ')
                data.append(String.format(Locale.ROOT, "%.3f", coords[i]).trimEnd('0').trimEnd('.'))
            }
            iterator.next()
        }
        val rule = if (shape.getPathIterator(null).windingRule == PathIterator.WIND_EVEN_ODD) "evenodd" else "nonzero"
        return """fill-rule="$rule" d="$data""""
    }

    // The colour on the path, as the IDE's own icons have it: recolouring them (a selected tool window
    // button, `toStrokeIcon`) patches each element's `fill`.
    private fun svg(path: String, color: String) =
        """<svg width="$SIZE" height="$SIZE" viewBox="0 0 $SIZE $SIZE" xmlns="http://www.w3.org/2000/svg"><path fill="$color" $path/></svg>"""

    private const val SIZE = 16

    /** The light and dark icon colours the codicons under `/codicons` are drawn in. */
    private const val LIGHT = "#6C707E"
    private const val DARK = "#CED0D6"

    private val LOG = logger<ContributedIcons>()
}
