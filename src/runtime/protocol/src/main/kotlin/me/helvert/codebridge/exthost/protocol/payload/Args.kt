package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/** A payload that is not the shape this build of VS Code was expected to send. */
class PayloadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The argument list of an RPC call, read by position.
 *
 * Every method on this protocol takes a JSON array whose meaning is positional and undeclared, so
 * reading one is always "argument 2 is the trigger characters". That was written out by hand at
 * every handler, and each did something different when the argument was missing: `args[0]` threw
 * `IndexOutOfBoundsException`, `getValue("path")` threw `NoSuchElementException`, and
 * `args.getOrNull(2) ?: emptyList()` silently carried on with a default. The first two say nothing
 * about which call was misread and the third says nothing at all -- and a missing argument almost
 * always means the method's shape changed in a newer VS Code.
 *
 * So there is one policy here. The plain accessors ([str], [int], [uri], ...) throw
 * [PayloadException] naming the method and the position, which is the sentence that identifies a
 * version skew. The `OrNull` accessors return null for an argument that is genuinely optional.
 */
class Args private constructor(
    /** The method these belong to, so a failure can say what was being read. */
    private val method: String,
    private val values: JsonArray,
) {

    val size: Int get() = values.size

    fun element(index: Int): JsonElement? = values.getOrNull(index)

    fun strOrNull(index: Int): String? = (element(index) as? JsonPrimitive)?.contentOrNull
    fun intOrNull(index: Int): Int? = (element(index) as? JsonPrimitive)?.intOrNull
    fun longOrNull(index: Int): Long? = (element(index) as? JsonPrimitive)?.longOrNull

    /** JavaScript has one number type, and some of what arrives here is genuinely fractional. */
    fun doubleOrNull(index: Int): Double? = (element(index) as? JsonPrimitive)?.doubleOrNull
    fun boolOrNull(index: Int): Boolean? = (element(index) as? JsonPrimitive)?.booleanOrNull
    fun objOrNull(index: Int): JsonObject? = element(index) as? JsonObject
    fun arrayOrNull(index: Int): JsonArray? = element(index) as? JsonArray
    fun uriOrNull(index: Int): UriComponents? = UriComponents.from(element(index))

    fun str(index: Int): String = strOrNull(index) ?: missing(index, "a string")
    fun int(index: Int): Int = intOrNull(index) ?: missing(index, "an integer")
    fun long(index: Int): Long = longOrNull(index) ?: missing(index, "an integer")
    fun double(index: Int): Double = doubleOrNull(index) ?: missing(index, "a number")
    fun bool(index: Int): Boolean = boolOrNull(index) ?: missing(index, "a boolean")
    fun obj(index: Int): JsonObject = objOrNull(index) ?: missing(index, "an object")
    fun array(index: Int): JsonArray = arrayOrNull(index) ?: missing(index, "an array")
    fun uri(index: Int): UriComponents = uriOrNull(index) ?: missing(index, "a resource")

    /** The strings of an array argument, or empty when it is absent -- as trigger characters are. */
    fun stringsOrEmpty(index: Int): List<String> =
        arrayOrNull(index)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    private fun missing(index: Int, expected: String): Nothing = throw PayloadException(
        "$method argument $index is not $expected; it was ${describe(element(index))}. " +
                "The method takes $size argument(s) here, so this may be a different VS Code build"
    )

    private fun describe(element: JsonElement?) = if (element == null) "absent" else "'$element'"

    companion object {

        fun of(method: String, argsJson: String): Args {
            val parsed = runCatching { Json.parseToJsonElement(argsJson) }
                .getOrElse { throw PayloadException("$method arguments are not JSON", it) }
            val array = parsed as? JsonArray
                ?: throw PayloadException("$method arguments are not a JSON array but $parsed")
            return Args(method, array)
        }

        /** For a payload that may legitimately not be an argument list at all. */
        fun ofOrNull(method: String, argsJson: String): Args? = runCatching { of(method, argsJson) }.getOrNull()
    }
}

/** A `cacheId` of `[list, index]`, as code actions and lenses are named for resolving; null for any other shape. */
internal fun cacheIdOf(value: JsonElement?): Pair<Int, Int>? {
    val id = value as? JsonArray ?: return null
    val list = (id.getOrNull(0) as? JsonPrimitive)?.intOrNull
    val index = (id.getOrNull(1) as? JsonPrimitive)?.intOrNull
    return if (list != null && index != null) list to index else null
}
