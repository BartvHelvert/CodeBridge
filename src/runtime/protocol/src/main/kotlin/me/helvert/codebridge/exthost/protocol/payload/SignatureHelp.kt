package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Signature help: the signature of the call the caret is in, with the argument being typed picked out.
 *
 * Read off the 1.143 bundle's `ExtHostLanguageFeatures` and its `SignatureHelpAdapter`. A reply is
 * kept by the host under its `id` until released, because the next request about the same call
 * names it as `activeSignatureHelp`, and the host hands the extension back its own object.
 */
object SignatureHelp {

    /** `$registerSignatureHelpProvider(handle, selector, {triggerCharacters, retriggerCharacters})`. */
    const val REGISTER: String = $$"$registerSignatureHelpProvider"
    const val PROVIDE: String = $$"$provideSignatureHelp"
    const val RELEASE: String = $$"$releaseSignatureHelp"

    /** `SignatureHelpTriggerKind`: asked for, a trigger character typed, or the text or caret moved. */
    const val TRIGGER_INVOKE: Int = 1
    const val TRIGGER_CHARACTER: Int = 2
    const val TRIGGER_CONTENT_CHANGE: Int = 3

    /**
     * A provider's registration.
     *
     * [triggerCharacters] open signature help -- usually `(` and `,`; [retriggerCharacters] only ask
     * again while it is open -- usually `)`, after which the provider answers with nothing and it
     * closes. An extension on the older overload gives only the first list.
     */
    data class Registration(
        val handle: Int,
        val selector: JsonElement,
        val triggerCharacters: List<String>,
        val retriggerCharacters: List<String>,
    )

    /**
     * One parameter of a signature.
     *
     * [range] is where in its signature's label the parameter is, which is what is picked out; null
     * when the extension named a parameter its label does not contain.
     */
    data class Parameter(val range: IntRange?, val documentation: String?)

    /** One signature: an overload, typically. [activeParameter] is its own, overriding the reply's. */
    data class Signature(
        val label: String,
        val documentation: String?,
        val parameters: List<Parameter>,
        val activeParameter: Int?,
    ) {
        /** The parameter being typed when the reply's own is [fallback]; null when it is none of them. */
        fun active(fallback: Int): Parameter? = parameters.getOrNull(activeParameter ?: fallback)
    }

    /**
     * A reply: the signatures, which one applies and which of its parameters is being typed.
     *
     * [dto] is the reply as it came, which is what a later request names as the help already shown.
     * [id] is what the host keeps it under, to be released once the help is gone.
     */
    data class Help(
        val id: Int?,
        val signatures: List<Signature>,
        val activeSignature: Int,
        val activeParameter: Int,
        val dto: JsonObject,
    )

    /**
     * Why signature help is being asked for: VS Code's `SignatureHelpContext`.
     *
     * [active] is the help on screen, when there is one; [isRetrigger] whether there is help on
     * screen at all, which the extension may still be told without being handed the reply itself.
     */
    data class Trigger(
        val kind: Int,
        val character: String? = null,
        val isRetrigger: Boolean = false,
        val active: JsonObject? = null,
    )

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        val metadata = args.element(2)
        // The older overload passes the trigger characters as a bare list; the host converts that
        // before sending, but nothing is lost by reading both.
        val triggers = when (metadata) {
            is JsonObject -> strings(metadata["triggerCharacters"])
            is JsonArray -> strings(metadata)
            else -> emptyList()
        }
        val retriggers = (metadata as? JsonObject)?.let { strings(it["retriggerCharacters"]) }.orEmpty()
        return Registration(args.int(0), args.element(1) ?: JsonNull, triggers, retriggers)
    }

    /** Arguments for `$provideSignatureHelp(handle, resource, position, context)`; the host appends the token. */
    fun provide(handle: Int, resource: UriComponents, position: Position, trigger: Trigger): String =
        JsonArray(listOf(
            JsonPrimitive(handle),
            resource.toJson(),
            position.toJson(),
            buildJsonObject {
                put("triggerKind", trigger.kind)
                trigger.character?.let { put("triggerCharacter", it) }
                put("isRetrigger", trigger.isRetrigger)
                trigger.active?.let { put("activeSignatureHelp", it) }
            },
        )).toString()

    /** Arguments for `$releaseSignatureHelp(handle, id)`. */
    fun release(handle: Int, id: Int): String = "[$handle,$id]"

    /**
     * Reads a `$provideSignatureHelp` reply, which is `undefined` when the provider had nothing.
     *
     * A reply with no signatures is still returned, because the host holds it under its id all the
     * same, and only the caller can let it go.
     */
    fun help(reply: String?): Help? {
        if (reply.isNullOrBlank()) return null
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonObject ?: return null
        val signatures = (parsed["signatures"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::signatureOf) }
        return Help(
            id = int(parsed["id"]),
            signatures = signatures,
            activeSignature = int(parsed["activeSignature"]) ?: 0,
            activeParameter = int(parsed["activeParameter"]) ?: 0,
            dto = parsed,
        )
    }

    private fun signatureOf(signature: JsonObject): Signature? {
        val label = (signature["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val parameters = (signature["parameters"] as? JsonArray).orEmpty().map { element ->
            val parameter = element as? JsonObject
            Parameter(parameter?.let { rangeIn(label, it["label"]) }, documentationOf(parameter?.get("documentation")))
        }
        return Signature(label, documentationOf(signature["documentation"]), parameters, int(signature["activeParameter"]))
    }

    /**
     * Where a parameter's label is in its signature's: given as `[start, end]`, or as text to find.
     *
     * Text is found as VS Code finds it, the first occurrence; an extension that means a later one
     * says so with offsets, which LSP's `labelOffsetSupport` lets a server do.
     */
    private fun rangeIn(signature: String, label: JsonElement?): IntRange? = when (label) {
        is JsonPrimitive -> label.contentOrNull?.takeIf { label.isString && it.isNotEmpty() }?.let { text ->
            signature.indexOf(text).takeIf { it >= 0 }?.let { it until it + text.length }
        }
        is JsonArray -> {
            val start = int(label.getOrNull(0))
            val end = int(label.getOrNull(1))
            if (start != null && end != null && start in 0..end && end <= signature.length) start until end else null
        }
        else -> null
    }

    /** A plain string or a `MarkdownString`, as written; blank is none. */
    private fun documentationOf(element: JsonElement?): String? =
        Markdown.text(element)?.let(Markdown::unescape)?.trim()?.takeIf { it.isNotEmpty() }

    private fun int(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}
