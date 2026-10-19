package me.helvert.codebridge.registry

import kotlinx.serialization.json.*

/**
 * Reads Open VSX API responses.
 *
 * Tolerant in the same way as the manifest and launch.json parsers: the registry adds fields over
 * time and not every extension populates every one, so a surprise in a single entry costs that
 * entry rather than the whole page.
 */
object OpenVsxJson {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Parses `/api/-/search`. Entries that cannot be read are skipped, not fatal. */
    fun parseSearch(text: String): RegistrySearch {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("search response is not a JSON object")

        val extensions = (root["extensions"] as? JsonArray).orEmpty()
            .mapNotNull { runCatching { extension(it as JsonObject) }.getOrNull() }

        return RegistrySearch(
            extensions = extensions,
            offset = root.int("offset") ?: 0,
            total = root.int("totalSize") ?: extensions.size,
        )
    }

    /** Parses `/api/{namespace}/{name}`. */
    fun parseDetail(text: String): RegistryDetail {
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("detail response is not a JSON object")

        val files = root["files"] as? JsonObject
        return RegistryDetail(
            extension = extension(root),
            license = root.string("license"),
            repository = root.string("repository"),
            homepage = root.string("homepage"),
            categories = root.strings("categories"),
            engineRange = (root["engines"] as? JsonObject)?.string("vscode"),
            extensionKind = root.strings("extensionKind"),
            dependencies = root.strings("dependencies"),
            downloads = (root["downloads"] as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
                ?.toMap()
                .orEmpty(),
            download = files?.string("download"),
            sha256 = files?.string("sha256"),
            readme = files?.string("readme"),
        )
    }

    private fun extension(node: JsonObject): RegistryExtension {
        val namespace = node.string("namespace")
            ?: throw IllegalArgumentException("entry has no namespace")
        val name = node.string("name")
            ?: throw IllegalArgumentException("entry has no name")
        return RegistryExtension(
            namespace = namespace,
            name = name,
            version = node.string("version").orEmpty(),
            displayName = node.string("displayName"),
            description = node.string("description"),
            downloadCount = node.int("downloadCount") ?: 0,
            averageRating = (node["averageRating"] as? JsonPrimitive)?.doubleOrNull,
            reviewCount = node.int("reviewCount") ?: 0,
            verified = node.boolean("verified"),
            deprecated = node.boolean("deprecated"),
            preview = node.boolean("preview"),
            // Search results carry the icon under `files`, the detail endpoint under the same key.
            iconUrl = (node["files"] as? JsonObject)?.string("icon"),
            timestamp = node.string("timestamp"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
