import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Writes `webview/vscode-theme.json`: every theme color and size VS Code registers, with its defaults.
 *
 * VS Code hands a webview all of them as CSS variables, and a theme sets few: the rest come from each
 * one's defaults, which name another color or transform one. The webview module resolves them the
 * same way, on top of the colors it takes from the IDE. Nothing ships them in a readable form, so they
 * are read from the registrations in the web workbench's bundle, in the pinned VSCodium's web package.
 *
 * The bundle is minified, so only the few forms its defaults take are understood; anything else fails
 * the build, naming the color, rather than leaving that color out.
 */
@CacheableTask
abstract class GenerateVsCodeTheme : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    /** Where the web package is kept; [url] and [sha256] say what is in it. */
    @get:Internal abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun generate() {
        val file = archive.get().asFile
        downloadVerified(url.get(), sha256.get(), file)
        val bundle = archives.tarTree(file).matching { include(BUNDLE) }.singleFile.readText()
        val names = namesIn(bundle)
        val colors = registrations(bundle, registering(bundle, "registerColor"), names)
        val sizes = registrations(bundle, registering(bundle, "registerSize"), names)
        val output = outputDirectory.get().file("webview/vscode-theme.json").asFile
        output.parentFile.mkdirs()
        output.writeText(json(mapOf("colors" to colors.toSortedMap(), "sizes" to sizes.toSortedMap())))
        logger.info("${colors.size} colors, ${sizes.size} sizes")
    }

    private companion object {
        const val BUNDLE = "out/vs/code/browser/workbench/workbench.js"
        const val NAME = "[A-Za-z_$][A-Za-z0-9_$]*"

        /** `function Zu(s,o){return{op:0,value:s,factor:o}}`: VS Code's ColorTransformType, in its order. */
        val OPS = listOf("darken", "lighten", "transparent", "opaque", "oneOf", "lessProminent", "ifDefinedThenElse", "mix")
        val NAMED_COLORS = mapOf(
            "white" to "#ffffff", "black" to "#000000", "red" to "#ff0000", "blue" to "#0000ff",
            "green" to "#00ff00", "cyan" to "#00ffff", "lightgrey" to "#d3d3d3", "transparent" to "#00000000",
        )
    }

    /** What a minified name in a default stands for, besides another color's id or a number. */
    private sealed interface Meaning
    private data class TransformConstructor(val op: String, val parameters: List<String>?) : Meaning
    private data object SizeConstructor : Meaning
    private data object ColorClass : Meaning
    private data object RgbaClass : Meaning

    private class Unsupported(message: String) : Exception(message)

    /** The minified name of `registerColor` or `registerSize`, the functions every registration calls. */
    private fun registering(bundle: String, method: String): String =
        Regex("""function ($NAME)\(([a-z,]+)\)\{return $NAME\.$method\(\2\)}""").find(bundle)?.groupValues?.get(1)
            ?: throw GradleException("No $method in the workbench bundle")

    private fun namesIn(bundle: String): MutableMap<String, Any> {
        val names = HashMap<String, Any>()
        // `registerColor` returns the color's id, which is what a default names another color by.
        val registerColor = Regex.escape(registering(bundle, "registerColor"))
        Regex("""($NAME)=$registerColor\("([A-Za-z0-9.-]+)"""").findAll(bundle).forEach {
            names[it.groupValues[1]] = it.groupValues[2]
        }
        // Transform constructors, by the op their body builds.
        Regex("""function ($NAME)\(([^)]*)\)\{return\{op:(\d+),([^}]*)}}""").findAll(bundle).forEach {
            val (function, parameters, op, body) = it.destructured
            names[function] = TransformConstructor(
                OPS[op.toInt()],
                if (parameters.startsWith("...")) null else body.split(",").map { member -> member.substringBefore(":") },
            )
        }
        // Size constructors, for one theme kind or all four alike.
        Regex("""function ($NAME)\(s,o="px"\)\{""").findAll(bundle).forEach { names[it.groupValues[1]] = SizeConstructor }
        names[Regex("""($NAME)=class s\{static fromHex\(""").find(bundle)!!.groupValues[1]] = ColorClass
        names[Regex("""($NAME)=class\{constructor\(o,e,t,i=1\)\{this\._rgbaBrand""").find(bundle)!!.groupValues[1]] = RgbaClass
        Regex("""(?:var |,)($NAME)=(\d+)(?=[,;])""").findAll(bundle).forEach {
            it.groupValues[2].toIntOrNull()?.let { value -> names.putIfAbsent(it.groupValues[1], value) }
        }
        return names
    }

    /** `{id: defaults}` for each `function("id", defaults, description...)` call. */
    private fun registrations(bundle: String, function: String, names: MutableMap<String, Any>): Map<String, Any?> {
        val found = LinkedHashMap<String, Any?>()
        for (match in Regex("""\b${Regex.escape(function)}\("([A-Za-z0-9.-]+)",""").findAll(bundle)) {
            val id = match.groupValues[1]
            val parser = Parser(bundle, match.range.last + 1, names)
            val value = try {
                parser.expression().also { parser.expect(",") }
            } catch (problem: Unsupported) {
                throw GradleException("Theme color $id: ${problem.message}")
            }
            // Not a description, localized or not: some other function of that name.
            if (parser.text != "d" && parser.kind != "str") continue
            found[id] = value
        }
        return found
    }

    /** One registration's default value, as the JSON the webview module reads. */
    private class Parser(val bundle: String, start: Int, val names: MutableMap<String, Any>) {
        private val matcher = TOKEN.matcher(bundle)
        private var position = start
        var kind = ""
        var text = ""

        init {
            advance()
        }

        fun advance() {
            matcher.region(position, bundle.length)
            if (!matcher.lookingAt()) throw Unsupported("unreadable text ${bundle.substring(position, minOf(bundle.length, position + 40))}")
            position = matcher.end()
            kind = listOf("str", "num", "name", "punct").first { matcher.group(it) != null }
            text = matcher.group(kind)
        }

        fun expect(expected: String) {
            if (text != expected) throw Unsupported("expected '$expected', found '$text'")
            advance()
        }

        private fun arguments(): List<Any?> {
            expect("(")
            val values = ArrayList<Any?>()
            while (text != ")") {
                values += expression()
                if (text == ",") advance()
            }
            advance()
            return values
        }

        fun expression(): Any? {
            var value = primary()
            while (text == "." || text == "(") {
                if (text == ".") {
                    advance()
                    val member = text
                    advance()
                    value = when {
                        text == "(" -> method(value, member, arguments())
                        value == ColorClass && member in NAMED_COLORS -> NAMED_COLORS.getValue(member)
                        else -> throw Unsupported("property $member")
                    }
                } else value = call(value, arguments())
            }
            return value
        }

        private fun primary(): Any? {
            val kind = kind
            val text = text
            advance()
            return when {
                kind == "str" -> unquote(text)
                kind == "num" -> text.toDouble().let { if (it == Math.floor(it) && "." !in text && "e" !in text) it.toInt() else it }
                text == "!" -> primary() !in listOf(true, 1)
                text == "{" -> objectLiteral()
                text == "(" -> expression().also { expect(")") }
                text == "new" -> {
                    val constructor = names[this.text]
                    advance()
                    construct(constructor, arguments())
                }
                text == "null" -> null
                kind == "name" -> names[text] ?: constant(text)
                else -> throw Unsupported("'$text'")
            }
        }

        private fun objectLiteral(): Map<String, Any?> {
            val members = LinkedHashMap<String, Any?>()
            while (text != "}") {
                val key = text.trim('"', '\'')
                advance()
                expect(":")
                members[key] = expression()
                if (text == ",") advance()
            }
            advance()
            val op = members.remove("op") ?: return members
            return linkedMapOf<String, Any?>("op" to OPS[op as Int]) + members
        }

        /** A name bound once, at the top of a module, to a value the defaults can hold. */
        private fun constant(name: String): Any? {
            val definitions = Regex("""(?:\bvar |,|;)${Regex.escape(name)}=(?!=)""").findAll(bundle).toList()
            if (definitions.size != 1) throw Unsupported("name $name, defined ${definitions.size} times")
            val value = Parser(bundle, definitions.single().range.last + 1, names).expression()
            if (value != null) names[name] = value
            return value
        }

        private fun construct(constructor: Any?, values: List<Any?>): Any? = when {
            constructor == RgbaClass -> {
                val (red, green, blue) = values.take(3).map { (it as Number).toInt() }
                val hex = "#%02x%02x%02x".format(red, green, blue)
                val alpha = values.getOrNull(3) as Number?
                if (alpha != null && alpha.toDouble() != 1.0) mapOf("op" to "transparent", "value" to hex, "factor" to alpha) else hex
            }
            constructor == ColorClass && (values[0] is String || values[0] is Map<*, *>) -> values[0]
            else -> throw Unsupported("new $constructor")
        }

        private fun call(function: Any?, values: List<Any?>): Any = when (function) {
            is TransformConstructor ->
                if (function.parameters == null) mapOf("op" to function.op, "values" to values)
                else linkedMapOf<String, Any?>("op" to function.op) + function.parameters.zip(values)
            SizeConstructor -> "${values[0]}${values.getOrNull(1) ?: "px"}"
            else -> throw Unsupported("call of $function")
        }

        private fun method(value: Any?, method: String, values: List<Any?>): Any = when {
            value == ColorClass && method == "fromHex" -> (values[0] as String).lowercase()
            method in setOf("transparent", "darken", "lighten") && (value is String || value is Map<*, *>) ->
                mapOf("op" to method, "value" to value, "factor" to values[0])
            else -> throw Unsupported("method $method")
        }

        private fun unquote(text: String): String {
            val body = text.substring(1, text.length - 1)
            if ('\\' !in body) return body
            return Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(body) { escape ->
                val code = escape.groupValues[1]
                when {
                    code.startsWith("u") && code.length == 5 -> code.substring(1).toInt(16).toChar().toString()
                    code == "n" -> "\n"
                    code == "t" -> "\t"
                    else -> code
                }
            }
        }

        companion object {
            val TOKEN: Pattern = Pattern.compile(
                """\s*(?:(?<str>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""" +
                    """|(?<num>\d*\.?\d+(?:e-?\d+)?)""" +
                    """|(?<name>$NAME)""" +
                    """|(?<punct>\.\.\.|[()\[\]{},:.!?]))"""
            )
        }
    }

    /** One line per entry, so a VSCodium bump's changes read as a diff of the generated file. */
    private fun json(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Number -> value.toDouble().let { if (it == Math.floor(it) && !it.isInfinite()) it.toLong().toString() else it.toString() }
        is List<*> -> value.joinToString(", ", "[", "]") { json(it) }
        is Map<*, *> ->
            if (indent.length < 4) value.entries.joinToString(",\n", "{\n", "\n$indent}") { (key, member) ->
                "$indent  ${json(key)}: ${json(member, "$indent  ")}"
            } else value.entries.joinToString(", ", "{", "}") { (key, member) -> "${json(key)}: ${json(member, indent)}" }
        else -> throw GradleException("Cannot write $value")
    }
}
