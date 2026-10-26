package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Code actions as the workbench asks for them: quick fixes, refactorings, source actions.
 *
 * Read off the 1.143 bundle's `ExtHostLanguageFeatures` and its `CodeActionAdapter`. A reply is a
 * list the host keeps cached under an id until told to release it, because an action may be
 * resolved or run long after it was offered; each action names its place in that list.
 */
object CodeActions {

    /** `$registerCodeActionSupport(handle, selector, metadata, displayName, extensionId, supportsResolve)`. */
    const val REGISTER: String = $$"$registerCodeActionSupport"
    const val PROVIDE: String = $$"$provideCodeActions"
    const val RESOLVE: String = $$"$resolveCodeAction"
    const val RELEASE: String = $$"$releaseCodeActions"
    /** `ExtHostCommands.$executeContributedCommand`, which runs the command an action carries. */
    const val EXECUTE: String = $$"$executeContributedCommand"

    /** `CodeActionTriggerType.Invoke`: the user asked, as opposed to the lightbulb asking for them. */
    const val TRIGGER_INVOKE: Int = 1

    /** A provider, and whether it fills in an action's edit only once the action is chosen. */
    data class Registration(val handle: Int, val selector: JsonElement, val supportsResolve: Boolean)

    /**
     * A command an action runs, as the host's command converter spelled it.
     *
     * [id] is often not the extension's own command but a delegating one the converter registered,
     * with the real command cached behind the single argument; running it through
     * `$executeContributedCommand` reaches the same place either way.
     */
    data class Command(val id: String, val title: String, val arguments: JsonArray) {

        companion object {
            fun from(element: JsonElement?): Command? {
                val command = element as? JsonObject ?: return null
                val id = (command["id"] as? JsonPrimitive)?.contentOrNull ?: return null
                return Command(id, (command["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    command["arguments"] as? JsonArray ?: JsonArray(emptyList()))
            }
        }
    }

    /**
     * One action offered.
     *
     * [cacheId] is `[listId, index]` and absent for an extension that returned a bare command
     * instead of a `CodeAction`, which cannot be resolved. [disabled] is the reason an extension
     * gave for showing an action it will not run.
     */
    data class Action(
        val title: String,
        val cacheId: Pair<Int, Int>?,
        val kind: String?,
        val preferred: Boolean,
        val edit: WorkspaceEdit?,
        val command: Command?,
        val disabled: String?,
    )

    /** A reply: the list id to release, and what was offered. */
    data class Offered(val cacheId: Int?, val actions: List<Action>)

    /** What `$resolveCodeAction` fills in. */
    data class Resolved(val edit: WorkspaceEdit?, val command: Command?)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.boolOrNull(5) == true)
    }

    /**
     * Arguments for `$provideCodeActions(handle, resource, rangeOrSelection, context)`.
     *
     * A plain range rather than a selection: the adapter accepts either, and a selection's
     * direction means nothing to an action. The token is left for the host to append.
     */
    fun provide(handle: Int, resource: UriComponents, range: Range, only: String? = null): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            range.toJson(),
            buildJsonObject {
                put("trigger", TRIGGER_INVOKE)
                if (only != null) put("only", only)
            },
        )).toString()

    /** Arguments for `$resolveCodeAction(handle, [listId, index])`. */
    fun resolve(handle: Int, cacheId: Pair<Int, Int>): String =
        buildJsonArray {
            add(handle)
            add(buildJsonArray { add(cacheId.first); add(cacheId.second) })
        }.toString()

    /** Arguments for `$releaseCodeActions(handle, listId)`. */
    fun release(handle: Int, cacheId: Int): String = "[$handle,$cacheId]"

    /**
     * Reads a `$provideCodeActions` reply, which is `undefined` when the provider had nothing.
     *
     * Field names are spelled out here, unlike a completion reply's minified letters.
     */
    fun offered(reply: String?): Offered {
        if (reply.isNullOrBlank()) return Offered(null, emptyList())
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject
            ?: return Offered(null, emptyList())
        val actions = (parsed["actions"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val action = entry as? JsonObject ?: return@mapNotNull null
            val title = (action["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val cacheId = cacheIdOf(action["cacheId"])
            Action(
                title = title,
                cacheId = cacheId,
                kind = (action["kind"] as? JsonPrimitive)?.contentOrNull,
                preferred = (action["isPreferred"] as? JsonPrimitive)?.booleanOrNull == true,
                edit = WorkspaceEdit.from(action["edit"]),
                command = Command.from(action["command"]),
                disabled = (action["disabled"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        return Offered((parsed["cacheId"] as? JsonPrimitive)?.intOrNull, actions)
    }

    /** Reads a `$resolveCodeAction` reply, `{}` when the provider added nothing. */
    fun resolved(reply: String?): Resolved {
        val parsed = reply?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return Resolved(null, null)
        return Resolved(WorkspaceEdit.from(parsed["edit"]), Command.from(parsed["command"]))
    }

    /** Arguments for `ExtHostCommands.$executeContributedCommand(id, ...arguments)`. */
    fun execute(command: Command): String = buildJsonArray {
        add(command.id)
        command.arguments.forEach(::add)
    }.toString()
}

/**
 * Formatting providers: the whole document, or a range of it.
 *
 * Both answer with a bare array of [TextEdit] against the document the host holds. The options are
 * VS Code's `FormattingOptions`, handed to the extension as they are.
 */
object Formatting {

    /** `$registerDocumentFormattingSupport(handle, selector, extensionId, displayName)`. */
    const val REGISTER_DOCUMENT: String = $$"$registerDocumentFormattingSupport"
    /** `$registerRangeFormattingSupport(handle, selector, extensionId, displayName, supportsRanges)`. */
    const val REGISTER_RANGE: String = $$"$registerRangeFormattingSupport"
    const val PROVIDE_DOCUMENT: String = $$"$provideDocumentFormattingEdits"
    const val PROVIDE_RANGE: String = $$"$provideDocumentRangeFormattingEdits"

    data class Registration(val handle: Int, val selector: JsonElement, val extensionName: String?)

    data class Options(val tabSize: Int, val insertSpaces: Boolean) {
        fun toJson(): JsonObject = buildJsonObject {
            put("tabSize", tabSize)
            put("insertSpaces", insertSpaces)
        }
    }

    fun registration(method: String, argsJson: String): Registration {
        val args = Args.of(method, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, args.strOrNull(3))
    }

    /** Arguments for `$provideDocumentFormattingEdits(handle, resource, options)`; token appended by the host. */
    fun provideDocument(handle: Int, resource: UriComponents, options: Options): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), options.toJson())).toString()

    /** Arguments for `$provideDocumentRangeFormattingEdits(handle, resource, range, options)`. */
    fun provideRange(handle: Int, resource: UriComponents, range: Range, options: Options): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), range.toJson(), options.toJson())).toString()
}
