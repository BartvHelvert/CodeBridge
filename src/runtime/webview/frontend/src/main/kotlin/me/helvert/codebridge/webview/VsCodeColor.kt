package me.helvert.codebridge.webview

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * VS Code's `Color`, which its theme defaults are computed with. Ported with its rounding, since a
 * default transforms a color that is itself often transformed: an RGBA color keeps whole channels
 * and an alpha to three places, and one made from HSL keeps that HSL for the next transform.
 */
internal class VsCodeColor private constructor(val r: Int, val g: Int, val b: Int, val a: Double, private val hsl: Hsla?) {

    private class Hsla(h: Double, s: Double, l: Double, a: Double) {
        val h = h.coerceIn(0.0, 360.0).toInt().toDouble()
        val s = round(s.coerceIn(0.0, 1.0), 3)
        val l = round(l.coerceIn(0.0, 1.0), 3)
        val a = round(a.coerceIn(0.0, 1.0), 3)
    }

    private val hsla: Hsla get() = hsl ?: fromRgba()

    val isOpaque: Boolean get() = a == 1.0

    /** As VS Code puts it in a webview's CSS variables. */
    val css: String get() =
        if (isOpaque) "#%02x%02x%02x".format(r, g, b)
        else "rgba($r, $g, $b, ${BigDecimal(a).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()})"

    fun lighten(factor: Double) = of(Hsla(hsla.h, hsla.s, hsla.l + hsla.l * factor, hsla.a))

    fun darken(factor: Double) = of(Hsla(hsla.h, hsla.s, hsla.l - hsla.l * factor, hsla.a))

    fun transparent(factor: Double) = rgba(r.toDouble(), g.toDouble(), b.toDouble(), a * factor)

    fun mix(other: VsCodeColor, ratio: Double): VsCodeColor {
        val t = ratio.coerceIn(0.0, 1.0)
        return rgba(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t, a + (other.a - a) * t)
    }

    fun makeOpaque(background: VsCodeColor): VsCodeColor {
        if (isOpaque || background.a != 1.0) return this
        return rgba(background.r - a * (background.r - r), background.g - a * (background.g - g), background.b - a * (background.b - b), 1.0)
    }

    fun isDarkerThan(other: VsCodeColor) = luminance() < other.luminance()

    private fun isLighterThan(other: VsCodeColor) = luminance() > other.luminance()

    private fun luminance(): Double {
        fun component(value: Int) = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return round(0.2126 * component(r) + 0.7152 * component(g) + 0.0722 * component(b), 4)
    }

    private fun fromRgba(): Hsla {
        val red = r / 255.0
        val green = g / 255.0
        val blue = b / 255.0
        val maximum = max(red, max(green, blue))
        val minimum = min(red, min(green, blue))
        var hue = 0.0
        var saturation = 0.0
        val lightness = (minimum + maximum) / 2
        val chroma = maximum - minimum
        if (chroma > 0) {
            saturation = min(if (lightness <= 0.5) chroma / (2 * lightness) else chroma / (2 - 2 * lightness), 1.0)
            hue = when (maximum) {
                red -> (green - blue) / chroma + (if (green < blue) 6 else 0)
                green -> (blue - red) / chroma + 2
                else -> (red - green) / chroma + 4
            }
            hue = jsRound(hue * 60)
        }
        return Hsla(hue, saturation, lightness, a)
    }

    companion object {
        val TRANSPARENT = rgba(0.0, 0.0, 0.0, 0.0)

        /** VS Code's `RGBA`: channels clamped and truncated, alpha clamped and rounded. */
        fun rgba(r: Double, g: Double, b: Double, a: Double = 1.0) = VsCodeColor(
            r.coerceIn(0.0, 255.0).toInt(), g.coerceIn(0.0, 255.0).toInt(), b.coerceIn(0.0, 255.0).toInt(),
            round(a.coerceIn(0.0, 1.0), 3), null,
        )

        fun of(color: java.awt.Color) = rgba(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble(), color.alpha / 255.0)

        /** `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa`; null for anything else. */
        fun hex(text: String): VsCodeColor? {
            if (!text.matches(Regex("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) return null
            val digits = text.substring(1)
            val channels = if (digits.length <= 4) digits.map { it.digitToInt(16) * 17 }
                else digits.chunked(2).map { it.toInt(16) }
            return rgba(channels[0].toDouble(), channels[1].toDouble(), channels[2].toDouble(), (channels.getOrNull(3) ?: 255) / 255.0)
        }

        /** VS Code's `Color.getLighterColor`: [of] lightened past [relative], by how far it falls short. */
        fun lighter(of: VsCodeColor, relative: VsCodeColor, factor: Double): VsCodeColor {
            if (of.isLighterThan(relative)) return of
            val own = of.luminance()
            val other = relative.luminance()
            return of.lighten((if (factor == 0.0) 0.5 else factor) * (other - own) / other)
        }

        /** VS Code's `Color.getDarkerColor`. */
        fun darker(of: VsCodeColor, relative: VsCodeColor, factor: Double): VsCodeColor {
            if (of.isDarkerThan(relative)) return of
            val own = of.luminance()
            val other = relative.luminance()
            return of.darken((if (factor == 0.0) 0.5 else factor) * (own - other) / own)
        }

        private fun of(hsla: Hsla): VsCodeColor {
            val hue = hsla.h / 360
            val (red, green, blue) = if (hsla.s == 0.0) Triple(hsla.l, hsla.l, hsla.l) else {
                val q = if (hsla.l < 0.5) hsla.l * (1 + hsla.s) else hsla.l + hsla.s - hsla.l * hsla.s
                val p = 2 * hsla.l - q
                Triple(hueToRgb(p, q, hue + 1.0 / 3), hueToRgb(p, q, hue), hueToRgb(p, q, hue - 1.0 / 3))
            }
            val rgba = rgba(jsRound(red * 255), jsRound(green * 255), jsRound(blue * 255), hsla.a)
            return VsCodeColor(rgba.r, rgba.g, rgba.b, rgba.a, hsla)
        }

        private fun hueToRgb(p: Double, q: Double, hue: Double): Double {
            val t = when {
                hue < 0 -> hue + 1
                hue > 1 -> hue - 1
                else -> hue
            }
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }

        /** JavaScript's `Math.round`, which rounds halves up rather than to even. */
        private fun jsRound(value: Double) = floor(value + 0.5)

        private fun round(value: Double, places: Int): Double {
            val scale = 10.0.pow(places)
            return jsRound(value * scale) / scale
        }
    }
}
