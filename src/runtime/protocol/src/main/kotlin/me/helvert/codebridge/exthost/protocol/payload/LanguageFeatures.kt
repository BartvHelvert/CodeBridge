package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.intOrNull

/**
 * The requests and registrations of `MainThreadLanguageFeatures` / `ExtHostLanguageFeatures`.
 *
 * Extensions surface their features *to the workbench*: providers are registered over this service
 * whether they are backed by a language server or written in plain JavaScript, and the workbench
 * calls them back the same way. That is what lets a bridge serve an ordinary published extension
 * without intercepting its language client.
 */
object LanguageFeatures {

    const val REGISTER_HOVER: String = $$"$registerHoverProvider"
    const val REGISTER_COMPLETIONS: String = $$"$registerCompletionsProvider"
    /** Not `...Provider`, as the other two are; the workbench spells this one "support". */
    const val REGISTER_DEFINITION: String = $$"$registerDefinitionSupport"
    const val PROVIDE_HOVER: String = $$"$provideHover"
    const val PROVIDE_DEFINITION: String = $$"$provideDefinition"
    /** The other ways to a place, each a "support" asked exactly as definitions are. */
    const val REGISTER_TYPE_DEFINITION: String = $$"$registerTypeDefinitionSupport"
    const val PROVIDE_TYPE_DEFINITION: String = $$"$provideTypeDefinition"
    const val REGISTER_IMPLEMENTATION: String = $$"$registerImplementationSupport"
    const val PROVIDE_IMPLEMENTATION: String = $$"$provideImplementation"
    const val REGISTER_HIGHLIGHTS: String = $$"$registerDocumentHighlightProvider"
    const val REGISTER_EVALUATABLE: String = $$"$registerEvaluatableExpressionProvider"
    const val PROVIDE_EVALUATABLE: String = $$"$provideEvaluatableExpression"
    const val PROVIDE_HIGHLIGHTS: String = $$"$provideDocumentHighlights"
    const val RELEASE_COMPLETIONS: String = $$"$releaseCompletionItems"
    /** "Support", like definitions. */
    const val REGISTER_REFERENCES: String = $$"$registerReferenceSupport"
    const val PROVIDE_REFERENCES: String = $$"$provideReferences"
    /** "Support" again. */
    const val REGISTER_RENAME: String = $$"$registerRenameSupport"
    const val RESOLVE_RENAME_LOCATION: String = $$"$resolveRenameLocation"
    const val PROVIDE_RENAME_EDITS: String = $$"$provideRenameEdits"

    /** A provider registration: its handle, and the document selector it offered to answer for. */
    data class Registration(val handle: Int, val selector: JsonElement)

    /**
     * One place an extension says a symbol is defined: VS Code's `LocationLink`.
     *
     * [target] is the whole definition and [selection] the identifier inside it, where the extension
     * distinguished them -- a `Location`, which is what a language client converts LSP's plain
     * `textDocument/definition` reply into, carries only the first. [origin] identifies the source
     * text to underline while the modifier key is held.
     */
    data class DefinitionLink(val resource: UriComponents, val target: Range, val selection: Range?, val origin: Range? = null) {

        /**
         * Where the caret belongs.
         *
         * The selection when there is one, so the user lands on the name rather than on the first
         * line of a body that may be a comment.
         */
        val caret: Position
            get() = (selection ?: target).let { Position(it.startLineNumber, it.startColumn) }
    }

    /** `$registerHoverProvider(handle, selector, extensionId, ...)`. */
    fun hoverRegistration(argsJson: String): Registration {
        val args = Args.of(REGISTER_HOVER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /** `$registerDefinitionSupport(handle, selector)`, which carries no extension id. */
    fun definitionRegistration(argsJson: String): Registration = locationRegistration(REGISTER_DEFINITION, argsJson)

    /**
     * `$registerTypeDefinitionSupport` or `$registerImplementationSupport` -- [method] says which --
     * both `(handle, selector)` as a definition's is. Their replies are definition links too, read by [definitionLinks].
     */
    fun locationRegistration(method: String, argsJson: String): Registration {
        val args = Args.of(method, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    fun highlightRegistration(argsJson: String): Registration {
        val args = Args.of(REGISTER_HIGHLIGHTS, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /** `$registerReferenceSupport(handle, selector)`, which also carries no extension id. */
    fun referenceRegistration(argsJson: String): Registration {
        val args = Args.of(REGISTER_REFERENCES, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /**
     * A rename provider, and whether it can say what is to be renamed before it is asked to rename.
     *
     * [resolvesLocation] is whether the extension implemented `prepareRename`; a language client
     * does exactly when its server supports `textDocument/prepareRename`.
     */
    data class RenameRegistration(val handle: Int, val selector: JsonElement, val resolvesLocation: Boolean)

    /** `$registerRenameSupport(handle, selector, supportsResolveInitialValues)`. */
    fun renameRegistration(argsJson: String): RenameRegistration {
        val args = Args.of(REGISTER_RENAME, argsJson)
        return RenameRegistration(args.int(0), args.element(1) ?: JsonNull, args.boolOrNull(2) == true)
    }

    /**
     * What a `$resolveRenameLocation` reply says: the range to rename and the text to start the new
     * name from, or why the symbol there cannot be renamed.
     *
     * A rejection carries no range; an answer carries no rejection.
     */
    data class RenameLocation(val range: Range?, val text: String?, val rejectReason: String?)

    /** Arguments for `$resolveRenameLocation(handle, resource, position)`; the host appends the token. */
    fun resolveRenameLocation(handle: Int, resource: UriComponents, position: Position): String =
        provideDefinition(handle, resource, position)

    /** The reply to [resolveRenameLocation], or null from a provider that had nothing to say. */
    fun renameLocation(reply: String?): RenameLocation? {
        if (reply.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val rejectReason = (parsed["rejectReason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val range = Range.from(parsed["range"])
        val text = (parsed["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (rejectReason == null && (range == null || text == null)) return null
        return RenameLocation(range, text, rejectReason)
    }

    /** Arguments for `$provideRenameEdits(handle, resource, position, newName)`; the host appends the token. */
    fun provideRenameEdits(handle: Int, resource: UriComponents, position: Position, newName: String): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            position.toJson(),
            JsonPrimitive(newName),
        )).toString()

    /** What a `$provideRenameEdits` reply says: the edit that renames, or why there is none. */
    data class RenameEdits(val edit: WorkspaceEdit?, val rejectReason: String?)

    /**
     * The reply to [provideRenameEdits], or null from a provider that had no answer.
     *
     * A rejection arrives as `{rejectReason}` with the edits left out: the host turns the error a
     * provider threw into that, so the message is the extension's own.
     */
    fun renameEdits(reply: String?): RenameEdits? {
        if (reply.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val rejectReason = (parsed["rejectReason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (rejectReason != null) return RenameEdits(null, rejectReason)
        return RenameEdits(WorkspaceEdit.from(parsed) ?: return null, null)
    }

    /** VS Code's `Location`: a file and a range in it, which is all a reference is. */
    data class Location(val resource: UriComponents, val range: Range)

    /** The wire protocol uses 3 for writes, 2 for reads and 1 for text occurrences. */
    data class DocumentHighlight(val range: Range, val kind: Int)

    fun documentHighlights(reply: String): List<DocumentHighlight> {
        val entries = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return entries.mapNotNull {
            val entry = it as? JsonObject ?: return@mapNotNull null
            val range = runCatching { Range.from(entry["range"]) }.getOrNull() ?: return@mapNotNull null
            DocumentHighlight(range, (entry["kind"] as? JsonPrimitive)?.intOrNull ?: 1)
        }
    }

    /**
     * `$registerCompletionsProvider(handle, selector, triggerCharacters, supportsResolve, extensionId)`.
     *
     * The trigger characters are the ones the extension wants to be asked on, beyond an ordinary
     * identifier; an extension that offers none still answers an explicitly invoked completion.
     * [CompletionRegistration.resolves] says whether it implements `resolveCompletionItem`; the host
     * keeps the items of one that does not in no list, so it has nothing to resolve.
     */
    fun completionRegistration(argsJson: String): CompletionRegistration {
        val args = Args.of(REGISTER_COMPLETIONS, argsJson)
        val registration = Registration(args.int(0), args.element(1) ?: JsonNull)
        return CompletionRegistration(registration, args.stringsOrEmpty(2), args.boolOrNull(3) == true)
    }

    data class CompletionRegistration(val registration: Registration, val triggerCharacters: List<String>, val resolves: Boolean)

    /** `$registerEvaluatableExpressionProvider(handle, selector, extensionId)`. */
    fun evaluatableRegistration(argsJson: String): Registration {
        val args = Args.of(REGISTER_EVALUATABLE, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull)
    }

    /**
     * What a debugger hover evaluates: the text at [range], unless the extension named an
     * [expression] to evaluate in its place -- `self.x` for an `x` the user pointed at, say.
     */
    data class EvaluatableExpression(val range: Range, val expression: String?)

    /** Arguments for `$provideEvaluatableExpression(handle, resource, position)`; the token is the host's. */
    fun provideEvaluatableExpression(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson(), position.toJson())).toString()

    /** The reply to [provideEvaluatableExpression]; null when the extension found nothing there. */
    fun evaluatableExpression(reply: String): EvaluatableExpression? {
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val range = Range.from(parsed["range"]) ?: return null
        val expression = (parsed["expression"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return EvaluatableExpression(range, expression)
    }

    /** Every `$unregister*` takes the handle first, and nothing else this side needs. */
    fun unregisteredHandle(method: String, argsJson: String): Int? =
        Args.ofOrNull(method, argsJson)?.intOrNull(0)

    /**
     * Arguments for `$provideHover(handle, resource, position, context)`.
     *
     * The cancellation token is absent on purpose: the host appends its own for a cancellable call,
     * so sending one here would shift every argument after it.
     */
    fun provideHover(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            position.toJson(),
            buildJsonObject { put("verbosityRequest", JsonNull) },
        )).toString()

    /** Arguments for `$provideCompletionItems(handle, resource, position, context)`. */
    fun provideCompletions(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            position.toJson(),
            // CompletionTriggerKind.Invoke: IntelliJ does not report which character started this.
            buildJsonObject { put("triggerKind", 0) },
        )).toString()

    /**
     * Arguments for `$provideDefinition(handle, resource, position)`.
     *
     * Shorter than the hover request by the context argument, and with the same token left off: the
     * host appends its own for a cancellable call. Type definitions and implementations are asked
     * with the same arguments.
     */
    fun provideDefinition(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            position.toJson(),
        )).toString()

    /** Document highlights use the same handle, resource and position arguments as definitions. */
    fun provideHighlights(handle: Int, resource: UriComponents, position: Position): String =
        provideDefinition(handle, resource, position)

    /**
     * Arguments for `$provideReferences(handle, resource, position, context)`.
     *
     * The declaration is asked for, as VS Code's own Find All References does: the list is what
     * the extension says the references are, and it decides whether that includes the definition.
     */
    fun provideReferences(handle: Int, resource: UriComponents, position: Position): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            position.toJson(),
            buildJsonObject { put("includeDeclaration", true) },
        )).toString()

    /**
     * The places a `$provideReferences` reply names: an array of `{uri, range}`, or nothing at all
     * from a provider that had no answer.
     */
    fun locations(reply: String): List<Location> {
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return parsed.mapNotNull { entry ->
            val location = entry as? JsonObject ?: return@mapNotNull null
            val resource = UriComponents.from(location["uri"]) ?: return@mapNotNull null
            Location(resource, Range.from(location["range"]) ?: return@mapNotNull null)
        }
    }

    /** Arguments for `$releaseCompletionItems(handle, cacheId)`, which the host holds until told. */
    fun releaseCompletions(handle: Int, cacheId: Int): String = "[$handle,$cacheId]"

    /**
     * The text of a `$provideHover` reply.
     *
     * The contents are `MarkdownString`s, one per contribution, whose plaintext has had its spaces
     * escaped. Still markdown: rendering it is the caller's. Each keeps only the command links it
     * was trusted with, since joined together their trust can no longer be told apart.
     */
    fun hoverText(reply: String): String? {
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() ?: return null
        val contents = (parsed as? JsonObject)?.get("contents") as? JsonArray ?: return null
        val text = contents.mapNotNull { Markdown.trustedText(it) }
            .joinToString(separator = PARAGRAPH)
            .let(Markdown::unescape)
            .trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /**
     * The places a `$provideDefinition` reply names.
     *
     * A bare array of `LocationLink`, with the field names spelled out -- unlike a completion reply,
     * which the runtime's minifier reduced to single letters. A provider that knows nothing answers
     * with an empty array, which is not a failure and reads the same way here.
     */
    fun definitionLinks(reply: String): List<DefinitionLink> {
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return parsed.mapNotNull { entry ->
            val link = entry as? JsonObject ?: return@mapNotNull null
            val resource = UriComponents.from(link["uri"]) ?: return@mapNotNull null
            val target = Range.from(link["range"]) ?: return@mapNotNull null
            DefinitionLink(resource, target, Range.from(link["targetSelectionRange"]), Range.from(link["originSelectionRange"]))
        }
    }

    /** VS Code returns one `MarkdownString` per contribution; a blank line keeps them apart. */
    private const val PARAGRAPH = "\n\n"
}
