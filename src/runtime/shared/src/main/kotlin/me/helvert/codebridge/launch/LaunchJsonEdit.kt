package me.helvert.codebridge.launch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.helvert.codebridge.workspace.Jsonc
import me.helvert.codebridge.workspace.JsoncEdit
import me.helvert.codebridge.workspace.JsoncEdit.Step

/** What changes in one entry: the keys set, and those removed. */
data class EntryChanges(val set: Map<String, JsonElement> = emptyMap(), val removed: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = set.isEmpty() && removed.isEmpty()

    companion object {
        /** What turns [before] into [after], key by key. */
        fun between(before: JsonObject, after: JsonObject): EntryChanges = EntryChanges(
            after.filter { (key, value) -> before[key] != value },
            before.keys - after.keys,
        )
    }
}

/**
 * Changes one entry of a `launch.json` -- or of a `.code-workspace` file's `launch` -- key by key,
 * leaving every other byte of the file as it was; see [JsoncEdit].
 */
object LaunchJsonEdit {

    enum class Section(val key: String) { CONFIGURATIONS("configurations"), COMPOUNDS("compounds") }

    /**
     * [text] with the entry named [name] in [section] changed by [changes]. [prefix] is the keys the
     * launch section is under: none in a `launch.json`, `launch` in a `.code-workspace` file.
     *
     * A configuration given a new `name` takes along the compounds of the same file that start it,
     * those naming it bare or with [folder], the folder of the file, as VS Code would look it up.
     */
    fun apply(text: String, prefix: List<String>, section: Section, name: String, changes: EntryChanges, folder: String? = null): String {
        val entries = entries(text, prefix, section.key)
        val index = entries.indexOfSingle(name) { it.name() }
        require("name" !in changes.removed) { "An entry must have a name" }
        val renamed = changes.set["name"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: throw IllegalArgumentException("An entry must have a name")
        }?.takeIf { it != name }
        require(renamed == null || entries.none { it.name() == renamed }) { "There already is an entry named '$renamed'" }
        val entry = buildList { prefix.mapTo(this, Step::Key); add(Step.Key(section.key)); add(Step.Index(index)) }
        var edited = text
        for (key in changes.removed) edited = JsoncEdit.set(edited, entry + Step.Key(key), null)
        for ((key, value) in changes.set) edited = JsoncEdit.set(edited, entry + Step.Key(key), value)
        if (renamed != null && section == Section.CONFIGURATIONS) edited = renameMembers(edited, prefix, name, renamed, folder)
        return edited
    }

    /** The entry named [name] in [section], as the file has it; null when there is not exactly one. */
    fun entry(text: String, prefix: List<String>, section: Section, name: String): JsonObject? =
        runCatching { entries(text, prefix, section.key).filter { it.name() == name } }.getOrNull()?.singleOrNull()

    private fun renameMembers(text: String, prefix: List<String>, from: String, to: String, folder: String?): String {
        var edited = text
        entries(text, prefix, Section.COMPOUNDS.key).forEachIndexed { compound, entry ->
            val members = entry["configurations"] as? JsonArray ?: return@forEachIndexed
            val path = prefix.map(Step::Key) + listOf(Step.Key(Section.COMPOUNDS.key), Step.Index(compound), Step.Key("configurations"))
            members.forEachIndexed { index, member ->
                @Suppress("IntroduceWhenSubject") // with a subject the compiler wants an empty `else`
                when {
                    member is JsonPrimitive && member.isString && member.content == from ->
                        edited = JsoncEdit.set(edited, path + Step.Index(index), JsonPrimitive(to))
                    member is JsonObject && member.name() == from && (member["folder"] as? JsonPrimitive)?.contentOrNull.let { it == null || it == folder } ->
                        edited = JsoncEdit.set(edited, path + listOf(Step.Index(index), Step.Key("name")), JsonPrimitive(to))
                }
            }
        }
        return edited
    }

    private fun entries(text: String, prefix: List<String>, section: String): List<JsonObject> {
        var node: JsonElement = Json.parseToJsonElement(Jsonc.stripToJson(text))
        for (key in prefix + section) node = (node as? JsonObject)?.get(key) ?: return emptyList()
        // Not objects are kept in place, so that an index is the file's.
        return (node as? JsonArray)?.map { it as? JsonObject ?: JsonObject(emptyMap()) }.orEmpty()
    }

    private fun JsonObject.name(): String? = (this["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun <T> List<T>.indexOfSingle(name: String, nameOf: (T) -> String?): Int {
        val matches = indices.filter { nameOf(this[it]) == name }
        require(matches.size == 1) { "Expected one entry named '$name'; found ${matches.size}" }
        return matches.single()
    }
}
