package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * A document's outline: what VS Code's sticky scroll, breadcrumbs and Outline view are drawn from.
 *
 * Read off the bundle's `ExtHostLanguageFeatures` and its `DocumentSymbolAdapter`. A reply is
 * always a tree: an extension that answers with flat `SymbolInformation`s has them nested by range
 * in the host before they are sent. Nothing is cached in the host, so nothing is released.
 */
object DocumentSymbols {

    /** `$registerDocumentSymbolProvider(handle, selector, label)`. */
    const val REGISTER: String = $$"$registerDocumentSymbolProvider"
    const val PROVIDE: String = $$"$provideDocumentSymbols"

    /** A provider's registration; [label] is what VS Code names it by in the Outline view. */
    data class Registration(val handle: Int, val selector: JsonElement, val label: String?)

    /**
     * One symbol, over **1-based** ranges in the text the provider was asked about.
     *
     * [range] is everything the symbol spans -- a function with its body, and its doc comment and
     * decorators where the provider counts them; [selectionRange] is its name.
     */
    data class Symbol(
        val name: String,
        val kind: Int,
        val range: Range,
        val selectionRange: Range,
        val detail: String = "",
        val children: List<Symbol> = emptyList(),
    )

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.int(0), args.element(1) ?: JsonNull, (args.element(2) as? JsonPrimitive)?.contentOrNull)
    }

    /** Arguments for `$provideDocumentSymbols(handle, resource)`; the token is left for the host to append. */
    fun provide(handle: Int, resource: UriComponents): String =
        JsonArray(listOf(JsonPrimitive(handle), resource.toJson())).toString()

    /** Reads a `$provideDocumentSymbols` reply, which is `undefined` when the provider had nothing. */
    fun symbols(reply: String?): List<Symbol> {
        if (reply.isNullOrBlank()) return emptyList()
        val parsed = runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray ?: return emptyList()
        return symbolsOf(parsed)
    }

    /**
     * [symbols] as the workbench's `DocumentSymbol`s, as `_executeDocumentSymbolProvider` answers.
     * `tags` is always there because the host reads it without asking whether it is.
     */
    fun toJson(symbols: List<Symbol>): String = JsonArray(symbols.map(::jsonOf)).toString()

    private fun jsonOf(symbol: Symbol): JsonObject = buildJsonObject {
        put("name", symbol.name)
        put("detail", symbol.detail)
        put("kind", symbol.kind)
        put("tags", JsonArray(emptyList()))
        put("range", symbol.range.toJson())
        put("selectionRange", symbol.selectionRange.toJson())
        put("children", JsonArray(symbol.children.map(::jsonOf)))
    }

    private fun symbolsOf(array: JsonArray?): List<Symbol> =
        array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::symbolOf) }

    private fun symbolOf(symbol: JsonObject): Symbol? {
        val range = Range.from(symbol["range"]) ?: return null
        return Symbol(
            name = (symbol["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            kind = (symbol["kind"] as? JsonPrimitive)?.intOrNull ?: 0,
            range = range,
            selectionRange = Range.from(symbol["selectionRange"]) ?: range,
            detail = (symbol["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            children = symbolsOf(symbol["children"] as? JsonArray),
        )
    }
}
