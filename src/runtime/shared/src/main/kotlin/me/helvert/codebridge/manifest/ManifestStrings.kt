package me.helvert.codebridge.manifest

import kotlinx.serialization.json.*
import me.helvert.codebridge.workspace.Jsonc
import java.nio.file.Files
import java.nio.file.Path

/**
 * The text a manifest shows the user, which it may write as `%key%` for `package.nls.json` to
 * supply: command titles, view names, launch snippet labels.
 */
object ManifestStrings {

    /** The strings of `package.nls.json` next to an extension's manifest, or none. */
    fun nls(extensionDirectory: Path): Map<String, String> {
        val file = extensionDirectory.resolve("package.nls.json")
        if (!Files.isRegularFile(file)) return emptyMap()
        val root =
            runCatching { Json.parseToJsonElement(Jsonc.stripToJson(Files.readString(file))) as? JsonObject }.getOrNull()
                ?: return emptyMap()
        // A value is the string, or an object carrying it as `message` beside a translator's comment.
        return root.mapNotNull { (key, value) ->
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: ((value as? JsonObject)?.get("message") as? JsonPrimitive)?.content
            text?.let { key to it }
        }.toMap()
    }

    /** [text], or the string [nls] has for it when it is a `%key%`. */
    fun localized(text: String, nls: Map<String, String>): String =
        if (text.length > 2 && text.startsWith('%') && text.endsWith('%')) nls[text.substring(1, text.length - 1)]
            ?: text else text

    /** [element] with every `%key%` string in it, however deep, replaced as [localized] does. */
    fun localized(element: JsonElement, nls: Map<String, String>): JsonElement = when {
        nls.isEmpty() -> element
        element is JsonObject -> JsonObject(element.mapValues { (_, value) -> localized(value, nls) })
        element is JsonArray -> JsonArray(element.map { localized(it, nls) })
        element is JsonPrimitive && element.isString -> {
            val text = localized(element.content, nls)
            if (text == element.content) element else JsonPrimitive(text)
        }

        else -> element
    }
}
