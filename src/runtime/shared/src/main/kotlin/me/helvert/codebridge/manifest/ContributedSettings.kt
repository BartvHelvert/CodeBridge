package me.helvert.codebridge.manifest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The settings extensions declare in `contributes.configuration`. */
object ContributedSettings {

    /** [manifest]'s settings with their schemas; `contributes.configuration` is one section object or an array of them. */
    fun of(manifest: VsixManifest): List<Pair<String, JsonElement>> {
        val sections = when (val configuration = (manifest.raw["contributes"] as? JsonObject)?.get("configuration")) {
            is JsonArray -> configuration
            is JsonObject -> listOf(configuration)
            else -> emptyList()
        }
        return sections.flatMap { section ->
            ((section as? JsonObject)?.get("properties") as? JsonObject)?.toList().orEmpty()
        }
    }

    /** Every setting the [manifests] declare, as `${config:...}` names them. */
    fun keys(manifests: List<VsixManifest>): List<String> =
        manifests.flatMap { manifest -> of(manifest).map { it.first } }.distinct()
}
