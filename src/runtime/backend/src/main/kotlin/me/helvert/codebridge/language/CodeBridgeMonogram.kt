package me.helvert.codebridge.language

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.StartupUiUtil
import java.awt.Color
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.PathIterator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.*
import javax.swing.Icon

/**
 * A drawn tile carrying the language's initial, for a language with no icon from anywhere else.
 *
 * VS Code's own icon theme, [me.helvert.codebridge.icons.FileIcons], covers the languages VS Code knows,
 * and an extension may declare one for its language; this is for a language that has neither. The
 * alternatives are all worse. The platform has no icon for a language it has never heard of: it
 * ships 81 file type icons, and every one of them is for a language the IDE handles itself, which
 * is exactly the set this plugin stands back from. Borrowing another plugin's icon cannot work
 * either, because a file only reaches this plugin when the plugin owning that icon is *not*
 * installed.
 *
 * So it is drawn rather than loaded. The letter comes from the language's own name and the colour
 * from its id, which makes every language look like itself and unlike its neighbours, with nothing
 * to license and nothing to keep up to date. It claims no resemblance to the language's real logo,
 * which is the honest position: the extension did not provide one.
 *
 * An outlined circle rather than a filled tile, following the IDE's own drawn icons. A solid 16px
 * square is the heaviest thing a file tree row can hold and read as shouting next to the platform's
 * own outlines; a circle inscribed in the same box covers a fifth less area, and carrying its
 * colour in a ring with a wash inside rather than a solid fill drops the weight again.
 *
 * Drawn as an SVG file and loaded through [IconLoader], not painted by a custom [Icon]. In split
 * mode the backend has to describe every icon to the frontend, and it can only describe images it
 * loaded: a class with its own `paintIcon` logs "Failed to serialize a model for icon" and never
 * reaches the frontend. The letter is written as its glyph outline rather than as SVG text, so it is the
 * IDE's own label font whatever the SVG renderer would have picked.
 *
 * An extension's own icon theme, `contributes.iconThemes`, is not read yet.
 */
internal class CodeBridgeMonogram(label: String, private val seed: String) {

    /** One character: a 16px tile cannot carry two legibly. */
    val letter: String = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    /** Loaded once each, on first paint: the platform asks for an icon on every row repaint. */
    private val lightIcon: Icon? by lazy { load(dark = false) }
    private val darkIcon: Icon? by lazy { load(dark = true) }

    /**
     * The image for the theme in use, or null if it could not be written or read.
     *
     * Read per call, as [CodeBridgeLanguageIcon] does, because the theme changes while the file type
     * instance -- compared by identity -- has to live on.
     */
    fun current(): Icon? = if (JBColor.isBright()) lightIcon else darkIcon

    /**
     * The image as SVG source, in the 16-unit box a file tree row is laid out in.
     *
     * Darker on a light theme and lighter on a dark one, because the same hue cannot carry a thin
     * ring and a small glyph against both backgrounds.
     */
    fun svg(dark: Boolean): String {
        val hue = hueOf(seed)
        val line = hex(if (dark) Color.getHSBColor(hue, 0.55f, 0.85f) else Color.getHSBColor(hue, 0.75f, 0.62f))
        // Inset by the ring's own width so the stroke is drawn inside the bounds rather than
        // straddling them, and by one more so the disc does not touch the row above and below.
        val radius = SIZE / 2f - 1f - STROKE / 2
        return """<svg xmlns="http://www.w3.org/2000/svg" width="$SIZE" height="$SIZE" viewBox="0 0 $SIZE $SIZE">""" +
            """<circle cx="${num(SIZE / 2f)}" cy="${num(SIZE / 2f)}" r="${num(radius)}" fill="$line" """ +
            """fill-opacity="${num(WASH_ALPHA / 255f)}" stroke="$line" stroke-width="${num(STROKE)}"/>""" +
            """<path d="${glyphPath()}" fill="$line"/></svg>"""
    }

    /** The letter's outline, centred the way the painted version centred it by its font metrics. */
    private fun glyphPath(): String {
        val font = StartupUiUtil.labelFont.deriveFont(Font.BOLD, SIZE * FONT_FRACTION)
        val context = FontRenderContext(null, true, true)
        val glyphs = font.createGlyphVector(context, letter)
        val lines = font.getLineMetrics(letter, context)
        val x = (SIZE - glyphs.logicalBounds.width.toFloat()) / 2
        val y = (SIZE - lines.height) / 2 + lines.ascent
        val path = StringBuilder()
        val iterator = glyphs.getOutline(x, y).getPathIterator(null)
        val points = FloatArray(6)
        while (!iterator.isDone) {
            when (iterator.currentSegment(points)) {
                PathIterator.SEG_MOVETO -> path.append("M").append(pairs(points, 1))
                PathIterator.SEG_LINETO -> path.append("L").append(pairs(points, 1))
                PathIterator.SEG_QUADTO -> path.append("Q").append(pairs(points, 2))
                PathIterator.SEG_CUBICTO -> path.append("C").append(pairs(points, 3))
                PathIterator.SEG_CLOSE -> path.append("Z")
            }
            iterator.next()
        }
        return path.toString()
    }

    private fun load(dark: Boolean): Icon? = runCatching {
        val file = write(svg(dark))
        IconLoader.findIcon(file.toUri().toURL(), true) ?: error("$file did not load as an icon")
    }.onFailure { LOG.warn("Could not draw the icon for a language that shipped none", it) }
        .getOrNull()

    private companion object {
        private val LOG = Logger.getInstance(CodeBridgeMonogram::class.java)

        /** One file tree row, in unscaled pixels; the platform scales from there. */
        private const val SIZE = 16

        /**
         * Large enough to read at 16px, small enough to clear the ring.
         *
         * Measured rather than guessed: at 0.64 a wide `H` and the tail of a `J` touch the stroke,
         * and above that they cross it.
         */
        private const val FONT_FRACTION = 0.60f

        /** The ring, in unscaled pixels. Thinner disappears at 100%; thicker crowds the letter. */
        private const val STROKE = 1.4f

        /** Enough tint to read as a disc, little enough that the ring still carries the colour. */
        private const val WASH_ALPHA = 56

        /** The unsigned hash, so a negative one does not fold two languages onto one hue. */
        private fun hueOf(seed: String): Float =
            ((seed.hashCode().toLong() and 0xFFFFFFFFL) % 360L).toFloat() / 360f

        /**
         * Named by content, so a file is never stale and never needs cleaning up after: a changed
         * drawing is a new name, and two IDEs sharing a system directory write identical bytes.
         */
        private fun write(svg: String): Path {
            val bytes = svg.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val name = digest.take(12).joinToString("") { "%02x".format(it) } + ".svg"
            val file = PathManager.getSystemDir().resolve("codebridge/monograms").resolve(name)
            if (Files.isRegularFile(file)) return file
            Files.createDirectories(file.parent)
            val partial = Files.createTempFile(file.parent, name, ".tmp")
            Files.write(partial, bytes)
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return file
        }

        private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)

        private fun pairs(points: FloatArray, count: Int) =
            (0 until count).joinToString(" ") { "${num(points[2 * it])} ${num(points[2 * it + 1])}" }

        /** Locale-free, and short: three decimals is a thousandth of a unit at 16 units. */
        private fun num(value: Float) = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}
