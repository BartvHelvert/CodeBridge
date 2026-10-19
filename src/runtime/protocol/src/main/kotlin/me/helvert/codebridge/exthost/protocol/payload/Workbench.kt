package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `MainThreadOutputService`: the channels an extension writes its own account of things to.
 *
 * The host keeps the text in a log file of its own and only reports how far it has got, so a
 * channel is tailed from that file rather than pushed line by line. This is where a language
 * server's side of a failure appears.
 */
object OutputChannels {

    const val REGISTER: String = $$"$register"
    const val UPDATE: String = $$"$update"
    const val REVEAL: String = $$"$reveal"
    const val CLOSE: String = $$"$close"
    const val DISPOSE: String = $$"$dispose"

    /** `OutputChannelUpdateMode.Append`, the only mode a tail has to act on. */
    const val APPEND: Int = 1

    /** `$register(name, fileUri, languageId, extensionId)`: a new channel and the file behind it. */
    data class Registration(val name: String, val file: UriComponents, val extensionId: String? = null)

    /** `$update(channelId, mode, till)`: how much of the file the host has written. */
    data class Update(val channelId: String, val mode: Int, val till: Long?)

    fun registration(argsJson: String): Registration {
        val args = Args.of(REGISTER, argsJson)
        return Registration(args.str(0), args.uri(1), args.strOrNull(3))
    }

    fun update(argsJson: String): Update {
        val args = Args.of(UPDATE, argsJson)
        return Update(args.str(0), args.intOrNull(1) ?: APPEND, args.longOrNull(2))
    }

    /** `$reveal`, `$close` and `$dispose` each name a channel and nothing else. */
    fun channelId(method: String, argsJson: String): String = Args.of(method, argsJson).str(0)

    /** The host reads the `$register` reply as the id itself, not as an object holding one. */
    fun registered(channelId: String): String = JsonPrimitive(channelId).toString()
}

/**
 * `MainThreadProgress`: an extension reporting how far along a piece of work is.
 *
 * `options.location` says where VS Code would have drawn it -- a notification, the window status
 * bar, a view -- and is deliberately not read: IntelliJ has one background-task surface that suits
 * all three.
 */
object Progress {

    const val START: String = $$"$startProgress"
    const val REPORT: String = $$"$progressReport"
    const val END: String = $$"$progressEnd"

    /** `$startProgress(handle, options, extensionId)`. */
    data class Start(val handle: Int, val title: String?, val cancellable: Boolean)

    /**
     * `$progressReport(handle, { message?, increment?, total? })`.
     *
     * [increment] is a percentage of the whole reported one slice at a time, not a running total,
     * so it accumulates rather than replacing what came before.
     */
    data class Step(val handle: Int, val message: String?, val increment: Double?)

    fun handle(method: String, argsJson: String): Int = Args.of(method, argsJson).int(0)

    fun start(argsJson: String): Start {
        val args = Args.of(START, argsJson)
        val options = args.objOrNull(1)
        return Start(
            handle = args.int(0),
            // An extension that titles nothing still shows a source; both are optional.
            title = options?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: options?.get("source")?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull,
            cancellable = options?.get("cancellable")?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun step(argsJson: String): Step {
        val args = Args.of(REPORT, argsJson)
        val step: JsonObject? = args.objOrNull(1)
        return Step(
            handle = args.int(0),
            message = step?.get("message")?.jsonPrimitive?.contentOrNull,
            increment = step?.get("increment")?.jsonPrimitive?.doubleOrNull,
        )
    }
}

/**
 * `MainThreadStatusBar`: the items an extension puts in the window's status bar.
 *
 * Language extensions use this to say whether their server is ready -- rust-analyzer spins an icon
 * while it loads a workspace and turns the item yellow or red when that fails -- so it is the one
 * place a user of the extension looks to learn why nothing is happening yet.
 *
 * The host re-sends the whole item on every change, so an entry is replaced rather than patched.
 */
object StatusBar {

    const val SET_ENTRY: String = $$"$setEntry"
    const val DISPOSE_ENTRY: String = $$"$disposeEntry"

    /**
     * What clicking the item runs, as the host converted it.
     *
     * A command with arguments that cannot cross the protocol arrives as the host's delegating
     * command with a cache key for its only argument, so running [id] with [arguments] is right
     * either way.
     */
    data class Command(val id: String, val arguments: JsonArray)

    /**
     * `$setEntry(entryId, id, extensionId, name, text, tooltip, hasTooltipProvider, command, color,
     * backgroundColor, alignLeft, priority, accessibilityInformation)`.
     *
     * [text] keeps its `$(icon)` references; drawing them is the UI's business. [tooltip] is plain
     * text, or Markdown when [markdown] -- a `MarkdownString` -- whose `command:` links VS Code runs
     * only when [trusts] says the extension trusted the string with that command. [themeIcons] is
     * whether `$(icon)`s in the tooltip are drawn as icons, as the string's `supportThemeIcons` asks.
     * [background] is the theme colour id, and the host only lets an extension use the warning and
     * error ones.
     */
    data class Entry(
        val entryId: String,
        val extensionId: String?,
        val name: String,
        val text: String,
        val tooltip: String?,
        val command: Command?,
        val background: String?,
        val alignLeft: Boolean,
        val priority: Double?,
        val markdown: Boolean = false,
        val themeIcons: Boolean = false,
        /** The commands the tooltip's links may run: null for any, as `isTrusted: true` allows. */
        val trustedCommands: Set<String>? = emptySet(),
    ) {
        /**
         * Whether a link in the tooltip may run [command]. VS Code runs a Markdown `command:` link
         * only when the string is trusted: `isTrusted: true` allows any command, `{ enabledCommands:
         * [...] }` only those listed, and anything else none. Following the same rule here is what
         * keeps a tooltip from becoming a way to run commands its extension did not mean to offer.
         */
        fun trusts(command: String): Boolean = trustedCommands == null || command in trustedCommands
    }

    fun entry(argsJson: String): Entry {
        val args = Args.of(SET_ENTRY, argsJson)
        // Plain text or a `MarkdownString`, which crosses as an object with its source in `value`.
        val markdown = args.element(5) as? JsonObject
        val tooltip = markdown?.get("value")?.jsonPrimitive?.contentOrNull
            ?: (args.element(5) as? JsonPrimitive)?.contentOrNull
        val command = args.objOrNull(7)?.let { command ->
            command["id"]?.jsonPrimitive?.contentOrNull?.let { id ->
                Command(id, command["arguments"] as? JsonArray ?: JsonArray(emptyList()))
            }
        }
        return Entry(
            entryId = args.str(0),
            extensionId = args.strOrNull(2),
            name = args.strOrNull(3) ?: args.strOrNull(1) ?: "",
            text = args.strOrNull(4) ?: "",
            tooltip = tooltip?.takeIf { it.isNotBlank() },
            command = command,
            background = args.objOrNull(9)?.get("id")?.jsonPrimitive?.contentOrNull,
            alignLeft = args.boolOrNull(10) ?: true,
            priority = args.doubleOrNull(11),
            markdown = markdown != null,
            themeIcons = (markdown?.get("supportThemeIcons") as? JsonPrimitive)?.booleanOrNull == true,
            trustedCommands = when (val trusted = markdown?.get("isTrusted")) {
                is JsonPrimitive -> if (trusted.booleanOrNull == true) null else emptySet()
                is JsonObject -> (trusted["enabledCommands"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()

                else -> emptySet()
            },
        )
    }

    /** The command a tooltip link's [href], `command:id?args`, runs; null for any other link. */
    fun linkCommand(href: String): Command? =
        Markdown.command(href)?.let { Command(it.id, it.arguments) }

    /** What a command link of the tooltip, once rendered, points at: this, then its index. */
    const val LINK_SCHEME: String = "codebridge-status:"

    fun entryId(argsJson: String): String = Args.of(DISPOSE_ENTRY, argsJson).str(0)
}

/**
 * `MainThreadConfiguration`: an extension changing a setting, as `getConfiguration().update` does.
 *
 * rust-analyzer's "Disable Check on Save" is one. In VS Code these land in a real settings file --
 * `.vscode/settings.json` for the workspace -- and are not temporary, whatever the command says.
 */
object Configuration {

    const val SERVICE: String = "MainThreadConfiguration"

    const val UPDATE: String = $$"$updateConfigurationOption"
    const val REMOVE: String = $$"$removeConfigurationOption"

    /**
     * VS Code's internal `ConfigurationTarget`, as the host sends it.
     *
     * `null` -- the extension named none -- means the workspace for anything but a per-resource
     * setting, and in a single-folder workspace the folder is the workspace, so both land in the
     * same file here.
     */
    enum class Target(val wire: Int) {
        APPLICATION(1), USER(2), USER_LOCAL(3), USER_REMOTE(4), WORKSPACE(5), WORKSPACE_FOLDER(6), DEFAULT(7), MEMORY(8);

        companion object {
            fun of(wire: Int?): Target? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * `$updateConfigurationOption(target, key, value, overrides, scopeToLanguage)`, or the remove
     * variant, which is the same without [value] -- so [value] null means "remove".
     *
     * [language] is set only when the extension asked for the language-scoped `"[id]"` section, and
     * [resource] is the file or folder a per-resource setting is written for, when one is named.
     */
    data class Write(val target: Target?, val key: String, val value: JsonElement?, val language: String?,
                     val resource: UriComponents? = null)

    fun write(method: String, argsJson: String): Write {
        val args = Args.of(method, argsJson)
        val removing = method == REMOVE
        val overrides = args.objOrNull(if (removing) 2 else 3)
        val scoped = args.boolOrNull(if (removing) 3 else 4) ?: false
        return Write(
            target = Target.of(args.intOrNull(0)),
            key = args.str(1),
            value = if (removing) null else args.element(2)?.takeUnless { it is JsonNull },
            language = overrides?.get("overrideIdentifier")?.jsonPrimitive?.contentOrNull?.takeIf { scoped },
            resource = overrides?.get("resource")?.let(UriComponents::from),
        )
    }
}
/**
 * `MainThreadMessageService`: `window.showInformationMessage` and its warning and error siblings.
 *
 * The one way an extension talks to the user directly -- rust-analyzer answers its own "Extension
 * Info" command with nothing else -- and, with items, the usual way it asks a question: the reply
 * is the handle of the item chosen, or nothing when the message was dismissed.
 */
object Messages {

    const val SERVICE: String = "MainThreadMessageService"

    const val SHOW: String = $$"$showMessage"

    /** VS Code's `Severity`: `Ignore` is 0 and is shown as information, as VS Code shows it. */
    enum class Severity { INFO, WARNING, ERROR }

    /** One `MessageItem`; [handle] is what the reply names it by. */
    data class Item(val title: String, val handle: Int, val closeAffordance: Boolean)

    /** `$showMessage(severity, message, { modal?, detail?, source? }, items)`. */
    data class Message(
        val severity: Severity,
        val text: String,
        val detail: String?,
        val modal: Boolean,
        val source: String?,
        val items: List<Item>,
    )

    fun message(argsJson: String): Message {
        val args = Args.of(SHOW, argsJson)
        val options = args.objOrNull(2)
        val items = args.arrayOrNull(3).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            Item(
                title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                handle = item["handle"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                closeAffordance = item["isCloseAffordance"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        return Message(
            severity = when (args.intOrNull(0)) {
                2 -> Severity.WARNING
                3 -> Severity.ERROR
                else -> Severity.INFO
            },
            text = args.strOrNull(1).orEmpty(),
            detail = options?.get("detail")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            modal = options?.get("modal")?.jsonPrimitive?.booleanOrNull ?: false,
            source = (options?.get("source") as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull,
            items = items,
        )
    }

    /** The reply: the chosen item's handle, or no value at all for a dismissal. */
    fun chosen(handle: Int?): String? = handle?.toString()
}
