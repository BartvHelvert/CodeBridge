package me.helvert.codebridge.exthost.protocol.payload

import java.nio.charset.Charset

/**
 * How a document's encoding is named on this protocol: VS Code's ids, never a charset name.
 *
 * `TextDocument.encoding` reports one, and `openTextDocument(uri, { encoding })` asks for one. VS Code
 * spells them as lowercase names with the punctuation removed -- `windows1252`, `shiftjis` -- and
 * picks one of the charset's names where it has several: `cp437`, not `ibm437`. So a charset is
 * matched by its name and every alias, normalized the same way, against the ids VS Code knows.
 */
object Encodings {

    const val UTF8: String = "utf8"

    /** UTF-8 with a byte-order mark. Decodes as [UTF8] does; only writing differs. */
    const val UTF8_BOM: String = "utf8bom"

    /** VS Code's encoding ids, in its order: the keys of 1.143's `SUPPORTED_ENCODINGS`, read off the bundle. */
    val SUPPORTED: List<String> = listOf(
        "utf8", "utf8bom", "utf16le", "utf16be", "windows1252", "iso88591", "iso88593", "iso885915",
        "macroman", "cp437", "windows1256", "iso88596", "windows1257", "iso88594", "iso885914",
        "windows1250", "iso88592", "cp852", "windows1251", "cp866", "cp1125", "iso88595", "koi8r",
        "koi8u", "iso885913", "windows1253", "iso88597", "windows1255", "iso88598", "iso885910",
        "iso885916", "windows1254", "iso88599", "cp857", "windows1258", "gbk", "gb18030", "cp950",
        "big5hkscs", "shiftjis", "eucjp", "euckr", "windows874", "iso885911", "koi8ru", "koi8t",
        "gb2312", "cp865", "cp850",
    )

    /**
     * The id VS Code knows [charset] by, or its normalized name where VS Code has none for it.
     *
     * [bom] distinguishes the two UTF-8 ids, which are one charset to Java.
     */
    fun idOf(charset: Charset, bom: Boolean = false): String {
        if (charset == Charsets.UTF_8) return if (bom) UTF8_BOM else UTF8
        val names = namesOf(charset)
        return SUPPORTED.firstOrNull { it in names } ?: normalize(charset.name())
    }

    /** The charset an id names, or null for one this JVM cannot decode. */
    fun charsetOf(id: String): Charset? {
        val wanted = normalize(id)
        if (wanted == UTF8 || wanted == UTF8_BOM) return Charsets.UTF_8
        return Charset.availableCharsets().values.firstOrNull { wanted in namesOf(it) }
    }

    private fun namesOf(charset: Charset): Set<String> =
        (charset.aliases() + charset.name()).mapTo(HashSet(), ::normalize)

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
}
