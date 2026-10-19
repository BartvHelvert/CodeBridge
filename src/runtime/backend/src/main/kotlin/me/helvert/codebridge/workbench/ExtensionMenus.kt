package me.helvert.codebridge.workbench

import kotlinx.serialization.json.*
import me.helvert.codebridge.extensions.ExtensionInstallationService
import me.helvert.codebridge.extensions.InstalledExtension
import me.helvert.codebridge.exthost.protocol.payload.WhenClause
import me.helvert.codebridge.icons.Codicons
import me.helvert.codebridge.manifest.ManifestStrings
import me.helvert.codebridge.rpc.TreeActionDto
import java.nio.file.Files

/**
 * The menus enabled extensions contribute (`contributes.menus`), such as `view/title` or
 * `debug/callstack/context`: which command each entry runs, under which `when` clause, in which group.
 *
 * Shown wherever IntelliJ has the matching menu; what is offered is decided here, against the
 * context keys of the place it is shown, so that only an entry on offer can be run.
 */
internal object ExtensionMenus {

    /**
     * One menu entry, with the command it names. [id] is its place in its menu across every
     * extension, which is what the UI names it by. A submenu (`contributes.submenus`) has no
     * command, and its [children] are the entries of the menu it names, numbered on from its own.
     */
    data class Entry(
        val id: Int,
        val command: String,
        val title: String,
        val icon: String?,
        val iconDark: String?,
        val group: String,
        val order: Double,
        val whenClause: String?,
        val enablement: String?,
        val children: List<Entry>? = null,
    ) {
        val submenu: Boolean get() = children != null

        fun enabled(context: (String) -> JsonElement?): Boolean = WhenClause.evaluate(enablement, context)

        fun toDto(context: (String) -> JsonElement?): TreeActionDto =
            TreeActionDto(id, title, icon, iconDark, group, enabled(context), children.orEmpty().map { it.toDto(context) })

        /** This entry and every entry of its submenus. */
        fun flatten(): List<Entry> = listOf(this) + children.orEmpty().flatMap { it.flatten() }
    }

    /**
     * Every entry of [menu] among the enabled extensions, with its command's title and icon. One
     * naming a command no extension declares is left out, as VS Code leaves it out; so is a submenu
     * no extension declares, or one that names itself.
     */
    fun entries(
        menu: String,
        image: (InstalledExtension, String?) -> String?,
        installed: List<InstalledExtension> = ExtensionInstallationService.getInstance().list().filter { it.enabled },
    ): List<Entry> {
        val commands = installed.flatMap { extension ->
            val nls by lazy { ManifestStrings.nls(extension.directory) }
            contributes(extension)?.get("commands").asObjects().mapNotNull { command ->
                val id = command.string("command") ?: return@mapNotNull null
                val title = command.string("title")?.let { ManifestStrings.localized(it, nls) } ?: id
                id to Triple(title, command, extension)
            }
        }.toMap()
        val submenus = installed.flatMap { extension ->
            val nls by lazy { ManifestStrings.nls(extension.directory) }
            contributes(extension)?.get("submenus").asObjects().mapNotNull { submenu ->
                val id = submenu.string("id") ?: return@mapNotNull null
                val label = submenu.string("label")?.let { ManifestStrings.localized(it, nls) } ?: id
                id to Triple(label, submenu, extension)
            }
        }.toMap()
        var next = 0
        fun icons(owner: InstalledExtension, declared: JsonObject) = when (val icon = declared["icon"]) {
            is JsonPrimitive -> image(owner, icon.contentOrNull) to null
            is JsonObject -> image(owner, icon.string("light")) to image(owner, icon.string("dark"))
            else -> null to null
        }
        fun menu(name: String, open: Set<String>): List<Entry> = installed.flatMap { extension ->
            (contributes(extension)?.get("menus") as? JsonObject)?.get(name).asObjects().mapNotNull { entry ->
                val id = next++
                val group = entry.string("group").orEmpty()
                fun make(title: String, icons: Pair<String?, String?>, enablement: String?, children: List<Entry>?) = Entry(
                    id = id, command = entry.string("command").orEmpty(), title = title, icon = icons.first, iconDark = icons.second,
                    group = group.substringBefore('@'),
                    order = group.substringAfter('@', "").toDoubleOrNull() ?: Double.MAX_VALUE,
                    whenClause = entry.string("when"), enablement = enablement, children = children,
                )
                val submenu = entry.string("submenu")
                if (submenu != null) {
                    if (submenu in open) return@mapNotNull null
                    val (label, declared, owner) = submenus[submenu] ?: return@mapNotNull null
                    return@mapNotNull make(label, icons(owner, declared), null, menu(submenu, open + submenu))
                }
                val command = entry.string("command") ?: return@mapNotNull null
                val (title, declared, owner) = commands[command] ?: return@mapNotNull null
                make(title, icons(owner, declared), declared.string("enablement"), null)
            }
        }
        return menu(menu, emptySet())
    }

    /**
     * The [entries] whose `when` clause holds in [context], in menu order: [buttons] first -- the group
     * VS Code shows as buttons there, such as `navigation` -- then by group, order and title.
     */
    fun offered(entries: List<Entry>, context: (String) -> JsonElement?, buttons: String? = null): List<Entry> =
        entries.filter { WhenClause.evaluate(it.whenClause, context) }
            // A submenu offers what its own entries' clauses allow, and is left out with none.
            .mapNotNull { entry -> if (!entry.submenu) entry else entry.copy(children = offered(entry.children.orEmpty(), context)).takeIf { it.children!!.isNotEmpty() } }
            .sortedWith(compareBy({ if (it.group == buttons) 0 else 1 }, { it.group }, { it.order }, { it.title }))

    /**
     * A `$(codicon)` as it is, or a file in [extension] as whatever [register] makes of it. The path is
     * the manifest's, so it is untrusted and must stay inside the extension.
     *
     * A file named after a codicon the IDE has an icon for is that codicon: extensions written before
     * menus took `$(codicon)` ship copies of them -- Gradle's `console.svg`, `list-tree.svg`,
     * `refresh.svg` -- and drawn as the IDE's own they match every other button beside them.
     */
    fun image(extension: InstalledExtension, value: String?, register: (java.nio.file.Path) -> String): String? {
        if (value.isNullOrBlank()) return null
        if (value.startsWith("$(")) return value
        val resolved = runCatching { extension.directory.resolve(value).normalize() }.getOrNull() ?: return null
        if (!resolved.startsWith(extension.directory) || !Files.isRegularFile(resolved)) return null
        val name = resolved.fileName.toString().substringBeforeLast('.')
        if (Codicons.known(name) != null) return "$($name)"
        return register(resolved)
    }

    private fun contributes(extension: InstalledExtension) = extension.manifest.raw["contributes"] as? JsonObject

    private fun JsonElement?.asObjects(): List<JsonObject> = (this as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
