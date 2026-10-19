package me.helvert.codebridge.icons

import com.intellij.openapi.diagnostic.logger
import org.brotli.dec.BrotliInputStream
import java.awt.Font
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * Icon fonts, as extensions contribute them for their own icons (`contributes.icons`): GitLens draws
 * its `$(gitlens-*)` icons from `glicons.woff2`.
 *
 * VS Code loads them as web fonts, so they come as TrueType, OpenType, WOFF or WOFF2. Java reads only
 * the first two, so a WOFF is unpacked and a WOFF2 rebuilt into the font it was made from first.
 */
object IconFonts {

    /** The font in [bytes], or null when it is none of those formats or cannot be read. */
    fun load(bytes: ByteArray): Font? = try {
        sfnt(bytes)?.let { Font.createFont(Font.TRUETYPE_FONT, it.inputStream()) }
    } catch (failure: Throwable) {
        // Errors too: a decoder class missing from the module's classloader is one.
        LOG.info("Could not read an icon font", failure)
        null
    }

    private val LOG = logger<IconFonts>()

    /** [bytes] as a TrueType or OpenType font; null for a format this cannot read. */
    internal fun sfnt(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4) return null
        return when (ByteBuffer.wrap(bytes).getInt(0)) {
            WOFF -> Woff.decode(bytes)
            WOFF2 -> Woff2.decode(bytes)
            TRUETYPE, OPENTYPE, TRUE -> bytes
            else -> null
        }
    }

    private const val WOFF = 0x774F4646 // wOFF
    private const val WOFF2 = 0x774F4632 // wOF2
    private const val TRUETYPE = 0x00010000
    private const val OPENTYPE = 0x4F54544F // OTTO
    private const val TRUE = 0x74727565 // true

    /** A table of the font being rebuilt. */
    private class Table(val tag: Int, val data: ByteArray)

    /** The font [tables] make up, under [flavor], laid out as the OpenType specification lays it out. */
    private fun assemble(flavor: Int, tables: List<Table>): ByteArray {
        val sorted = tables.sortedBy { it.tag.toUInt() }
        val count = sorted.size
        var power = 1
        var log = 0
        while (power * 2 <= count) {
            power *= 2; log++
        }
        val headerSize = 12 + 16 * count
        val size = headerSize + sorted.sumOf { padded(it.data.size) }
        val out = ByteBuffer.allocate(size)
        out.putInt(flavor).putShort(count.toShort())
            .putShort((power * 16).toShort()).putShort(log.toShort()).putShort((count * 16 - power * 16).toShort())
        var offset = headerSize
        for (table in sorted) {
            out.putInt(table.tag).putInt(checksum(table.data)).putInt(offset).putInt(table.data.size)
            offset += padded(table.data.size)
        }
        for (table in sorted) {
            out.put(table.data)
            repeat(padded(table.data.size) - table.data.size) { out.put(0) }
        }
        return out.array()
    }

    private fun padded(size: Int) = (size + 3) and 3.inv()

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (i in data.indices step 4) {
            var word = 0
            for (j in 0 until 4) word = (word shl 8) or (if (i + j < data.size) data[i + j].toInt() and 0xFF else 0)
            sum += word
        }
        return sum
    }

    /** WOFF 1.0: the font's tables, each zlib-compressed unless that would not make it smaller. */
    private object Woff {
        fun decode(bytes: ByteArray): ByteArray {
            val input = ByteBuffer.wrap(bytes)
            val flavor = input.getInt(4)
            val count = input.getShort(12).toInt() and 0xFFFF
            val tables = (0 until count).map { index ->
                val entry = 44 + 20 * index
                val tag = input.getInt(entry)
                val offset = input.getInt(entry + 4)
                val compressed = input.getInt(entry + 8)
                val length = input.getInt(entry + 12)
                val data = bytes.copyOfRange(offset, offset + compressed)
                Table(tag, if (compressed < length) inflate(data, length) else data)
            }
            return assemble(flavor, tables)
        }

        private fun inflate(data: ByteArray, length: Int): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArray(length)
                var done = 0
                while (done < length && !inflater.finished()) {
                    val read = inflater.inflate(out, done, length - done)
                    if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    done += read
                }
                check(done == length) { "A WOFF table is shorter than it says" }
                return out
            } finally {
                inflater.end()
            }
        }
    }

    /**
     * WOFF 2.0: the font's tables Brotli-compressed as one stream, `glyf` and `loca` -- and
     * possibly `hmtx` -- transformed first so they compress better. Undoing the transforms is the
     * reference decoder's (google/woff2, `woff2_dec.cc`) reconstruction, written out here.
     */
    private object Woff2 {
        fun decode(bytes: ByteArray): ByteArray? {
            val input = ByteBuffer.wrap(bytes)
            val flavor = input.getInt(4)
            // A collection holds several fonts; an icon font is one.
            if (flavor == TTCF) return null
            val count = input.getShort(12).toInt() and 0xFFFF
            val compressedSize = input.getInt(20)
            input.position(48)
            val entries = (0 until count).map {
                val flags = input.get().toInt() and 0xFF
                val tag = if (flags and 0x3F == 0x3F) input.getInt() else KNOWN_TAGS[flags and 0x3F]
                val version = flags shr 6
                val length = base128(input)
                val transformed = if (tag == GLYF || tag == LOCA) version == 0 else version != 0
                Entry(tag, transformed, if (transformed) base128(input) else length)
            }
            val compressed = bytes.copyOfRange(input.position(), input.position() + compressedSize)
            val stream = BrotliInputStream(compressed.inputStream()).use { it.readAllBytes() }
            var offset = 0
            val data = entries.associate { entry ->
                val table = stream.copyOfRange(offset, offset + entry.stored)
                offset += entry.stored
                entry.tag to table
            }
            val tables = ArrayList<Table>()
            val glyf = entries.firstOrNull { it.tag == GLYF }
            var glyphs: Glyphs? = null
            if (glyf != null && glyf.transformed) {
                glyphs = Glyphs.rebuild(data.getValue(GLYF))
                tables += Table(GLYF, glyphs.glyf)
                tables += Table(LOCA, glyphs.loca)
            }
            for (entry in entries) {
                if (glyphs != null && (entry.tag == GLYF || entry.tag == LOCA)) continue
                val table = data.getValue(entry.tag)
                tables += when {
                    entry.tag == HMTX && entry.transformed -> Table(HMTX, hmtx(table, data, glyphs ?: return null))
                    entry.transformed -> return null
                    else -> Table(entry.tag, table)
                }
            }
            return assemble(flavor, tables)
        }

        private class Entry(val tag: Int, val transformed: Boolean, val stored: Int)

        /** The `glyf` and `loca` tables rebuilt from the transformed `glyf`, and each glyph's xMin. */
        private class Glyphs(val glyf: ByteArray, val loca: ByteArray, val xMins: ShortArray) {
            companion object {
                fun rebuild(table: ByteArray): Glyphs {
                    val header = ByteBuffer.wrap(table)
                    header.getShort() // reserved
                    val options = header.getShort().toInt() and 0xFFFF
                    val count = header.getShort().toInt() and 0xFFFF
                    val longOffsets = header.getShort().toInt() != 0
                    val sizes = IntArray(7) { header.getInt() }
                    var position = header.position()
                    val streams = sizes.map { size ->
                        ByteBuffer.wrap(table, position, size).slice().also { position += size }
                    }
                    val contours = streams[0]
                    val points = streams[1]
                    val flags = streams[2]
                    val glyphStream = streams[3]
                    val composites = streams[4]
                    val boxes = streams[5]
                    val instructions = streams[6]
                    val overlaps =
                        if (options and 1 != 0) table.copyOfRange(position, position + (count + 7) / 8) else null
                    val boxBitmap = ByteArray(4 * ((count + 31) / 32)).also { boxes.get(it) }

                    val glyf = ByteArrayOutputStream()
                    val offsets = IntArray(count + 1)
                    val xMins = ShortArray(count)
                    for (glyph in 0 until count) {
                        offsets[glyph] = glyf.size()
                        val explicitBox = boxBitmap[glyph shr 3].toInt() and (0x80 shr (glyph and 7)) != 0
                        val contourCount = contours.getShort().toInt()
                        val written = when {
                            contourCount == 0 -> ByteArray(0)
                            contourCount < 0 -> composite(composites, glyphStream, instructions, boxes)
                            else -> simple(
                                contourCount, points, flags, glyphStream, instructions,
                                if (explicitBox) boxes else null,
                                overlaps != null && overlaps[glyph shr 3].toInt() and (0x80 shr (glyph and 7)) != 0,
                            )
                        }
                        if (written.size >= 4) xMins[glyph] = ByteBuffer.wrap(written).getShort(2)
                        glyf.write(written)
                        repeat(padded(written.size) - written.size) { glyf.write(0) }
                    }
                    offsets[count] = glyf.size()
                    val loca = ByteBuffer.allocate((count + 1) * if (longOffsets) 4 else 2)
                    for (offset in offsets) if (longOffsets) loca.putInt(offset) else loca.putShort((offset / 2).toShort())
                    return Glyphs(glyf.toByteArray(), loca.array(), xMins)
                }

                /** A composite glyph: its components as they were, with its bounding box and instructions. */
                private fun composite(
                    composites: ByteBuffer,
                    glyphs: ByteBuffer,
                    instructions: ByteBuffer,
                    boxes: ByteBuffer
                ): ByteArray {
                    val start = composites.position()
                    var hasInstructions = false
                    do {
                        val flags = composites.getShort().toInt() and 0xFFFF
                        if (flags and WE_HAVE_INSTRUCTIONS != 0) hasInstructions = true
                        var skip = 2 + if (flags and ARG_1_AND_2_ARE_WORDS != 0) 4 else 2
                        skip += when {
                            flags and WE_HAVE_A_SCALE != 0 -> 2
                            flags and WE_HAVE_AN_X_AND_Y_SCALE != 0 -> 4
                            flags and WE_HAVE_A_TWO_BY_TWO != 0 -> 8
                            else -> 0
                        }
                        composites.position(composites.position() + skip)
                    } while (flags and MORE_COMPONENTS != 0)
                    val components = ByteArray(composites.position() - start)
                    composites.duplicate().position(start).get(components)
                    val code = if (hasInstructions) ByteArray(uint255(glyphs)).also { instructions.get(it) } else null
                    val out = ByteBuffer.allocate(10 + components.size + (code?.let { 2 + it.size } ?: 0))
                    out.putShort(-1)
                    repeat(4) { out.putShort(boxes.getShort()) }
                    out.put(components)
                    if (code != null) out.putShort(code.size.toShort()).put(code)
                    return out.array()
                }

                /** A simple glyph: its points, delta-encoded in the triplet stream, written as `glyf` writes them. */
                private fun simple(
                    contourCount: Int, points: ByteBuffer, flagStream: ByteBuffer, glyphs: ByteBuffer,
                    instructions: ByteBuffer, boxes: ByteBuffer?, overlap: Boolean,
                ): ByteArray {
                    val ends = IntArray(contourCount)
                    var total = 0
                    for (contour in 0 until contourCount) {
                        total += uint255(points)
                        ends[contour] = total - 1
                    }
                    val xs = IntArray(total)
                    val ys = IntArray(total)
                    val onCurve = BooleanArray(total)
                    var x = 0
                    var y = 0
                    for (point in 0 until total) {
                        val raw = flagStream.get().toInt() and 0xFF
                        onCurve[point] = raw and 0x80 == 0
                        val flag = raw and 0x7F
                        val dx: Int
                        val dy: Int
                        when {
                            flag < 10 -> {
                                dx = 0
                                dy = sign(flag, ((flag and 14) shl 7) + u8(glyphs))
                            }

                            flag < 20 -> {
                                dx = sign(flag, (((flag - 10) and 14) shl 7) + u8(glyphs))
                                dy = 0
                            }

                            flag < 84 -> {
                                val b0 = flag - 20
                                val b1 = u8(glyphs)
                                dx = sign(flag, 1 + (b0 and 0x30) + (b1 shr 4))
                                dy = sign(flag shr 1, 1 + ((b0 and 0x0C) shl 2) + (b1 and 0x0F))
                            }

                            flag < 120 -> {
                                val b0 = flag - 84
                                dx = sign(flag, 1 + ((b0 / 12) shl 8) + u8(glyphs))
                                dy = sign(flag shr 1, 1 + (((b0 % 12) shr 2) shl 8) + u8(glyphs))
                            }

                            flag < 124 -> {
                                val b0 = u8(glyphs)
                                val b1 = u8(glyphs)
                                val b2 = u8(glyphs)
                                dx = sign(flag, (b0 shl 4) + (b1 shr 4))
                                dy = sign(flag shr 1, ((b1 and 0x0F) shl 8) + b2)
                            }

                            else -> {
                                dx = sign(flag, (u8(glyphs) shl 8) + u8(glyphs))
                                dy = sign(flag shr 1, (u8(glyphs) shl 8) + u8(glyphs))
                            }
                        }
                        x += dx
                        y += dy
                        xs[point] = x
                        ys[point] = y
                    }
                    val code = ByteArray(uint255(glyphs)).also { instructions.get(it) }
                    val box = if (boxes != null) IntArray(4) { boxes.getShort().toInt() }
                    else if (total == 0) IntArray(4)
                    else intArrayOf(xs.min(), ys.min(), xs.max(), ys.max())

                    val flags = ByteArrayOutputStream()
                    val xBytes = ByteArrayOutputStream()
                    val yBytes = ByteArrayOutputStream()
                    var lastX = 0
                    var lastY = 0
                    for (point in 0 until total) {
                        var flag = if (onCurve[point]) ON_CURVE else 0
                        if (overlap && point == 0) flag = flag or OVERLAP_SIMPLE
                        val dx = xs[point] - lastX
                        val dy = ys[point] - lastY
                        flag = flag or coordinate(dx, X_SHORT, X_SAME, xBytes)
                        flag = flag or coordinate(dy, Y_SHORT, Y_SAME, yBytes)
                        flags.write(flag)
                        lastX = xs[point]
                        lastY = ys[point]
                    }
                    val out =
                        ByteBuffer.allocate(10 + 2 * contourCount + 2 + code.size + flags.size() + xBytes.size() + yBytes.size())
                    out.putShort(contourCount.toShort())
                    box.forEach { out.putShort(it.toShort()) }
                    ends.forEach { out.putShort(it.toShort()) }
                    out.putShort(code.size.toShort()).put(code)
                    out.put(flags.toByteArray()).put(xBytes.toByteArray()).put(yBytes.toByteArray())
                    return out.array()
                }

                /** Writes [delta] as briefly as `glyf` allows, returning the flag bits that say how. */
                private fun coordinate(delta: Int, short: Int, same: Int, out: ByteArrayOutputStream): Int =
                    when (delta) {
                        0 -> same
                        in -255..255 -> {
                        out.write(kotlin.math.abs(delta))
                        short or if (delta > 0) same else 0
                    }

                    else -> {
                        out.write(delta shr 8 and 0xFF)
                        out.write(delta and 0xFF)
                        0
                    }
                }

                private fun sign(flag: Int, value: Int) = if (flag and 1 != 0) value else -value
            }
        }

        /**
         * The transformed `hmtx`: the left side bearings it leaves out are the glyphs' xMin, which is
         * what they almost always are.
         */
        private fun hmtx(table: ByteArray, data: Map<Int, ByteArray>, glyphs: Glyphs): ByteArray {
            val count = glyphs.xMins.size
            val metrics = ByteBuffer.wrap(data.getValue(HHEA)).getShort(34).toInt() and 0xFFFF
            val input = ByteBuffer.wrap(table)
            val flags = input.get().toInt()
            val advances = ShortArray(metrics) { input.getShort() }
            val bearings = ShortArray(metrics) { if (flags and 1 == 0) input.getShort() else glyphs.xMins[it] }
            val monospaced =
                ShortArray(count - metrics) { if (flags and 2 == 0) input.getShort() else glyphs.xMins[metrics + it] }
            val out = ByteBuffer.allocate(4 * metrics + 2 * (count - metrics))
            for (i in 0 until metrics) out.putShort(advances[i]).putShort(bearings[i])
            monospaced.forEach { out.putShort(it) }
            return out.array()
        }

        private fun u8(buffer: ByteBuffer) = buffer.get().toInt() and 0xFF

        private fun base128(buffer: ByteBuffer): Int {
            var value = 0
            repeat(5) {
                val byte = u8(buffer)
                value = (value shl 7) or (byte and 0x7F)
                if (byte and 0x80 == 0) return value
            }
            error("A WOFF2 number runs on")
        }

        private fun uint255(buffer: ByteBuffer): Int = when (val code = u8(buffer)) {
            253 -> buffer.getShort().toInt() and 0xFFFF
            254 -> u8(buffer) + 253 * 2
            255 -> u8(buffer) + 253
            else -> code
        }

        private fun tag(name: String) = name.fold(0) { tag, char -> (tag shl 8) or char.code }

        private val TTCF = tag("ttcf")
        private val GLYF = tag("glyf")
        private val LOCA = tag("loca")
        private val HMTX = tag("hmtx")
        private val HHEA = tag("hhea")

        /** The tags WOFF2 gives a number to, in its order. */
        private val KNOWN_TAGS = listOf(
            "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep",
            "CFF ", "VORG", "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE",
            "GDEF", "GPOS", "GSUB", "EBSC", "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt",
            "avar", "bdat", "bloc", "bsln", "cvar", "fdsc", "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar",
            "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat", "Gloc", "Feat", "Sill",
        ).map(::tag)

        private const val ON_CURVE = 0x01
        private const val X_SHORT = 0x02
        private const val Y_SHORT = 0x04
        private const val X_SAME = 0x10
        private const val Y_SAME = 0x20
        private const val OVERLAP_SIMPLE = 0x40

        private const val ARG_1_AND_2_ARE_WORDS = 0x0001
        private const val WE_HAVE_A_SCALE = 0x0008
        private const val MORE_COMPONENTS = 0x0020
        private const val WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
        private const val WE_HAVE_A_TWO_BY_TWO = 0x0080
        private const val WE_HAVE_INSTRUCTIONS = 0x0100
    }
}
