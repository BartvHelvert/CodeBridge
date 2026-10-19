package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.*

/**
 * The workbench's own commands that extensions run with `commands.executeCommand`.
 *
 * VS Code registers these in the renderer, so no extension does, and they reach the workbench by id
 * with their arguments. The host converts the API types among them first -- a `Position` arrives as
 * the editor model's 1-based `{lineNumber, column}`, a `Range` as a Monaco range, a `Location` as
 * `{uri, range}` -- but an extension passing a plain object sends it as it is, and LSP-shaped
 * objects, 0-based `{line, character}`, are common enough to read too.
 */
object WorkbenchCommands {

    /** `editor.action.showReferences(uri, position, locations)`: VS Code's references peek. */
    const val SHOW_REFERENCES = "editor.action.showReferences"

    data class ShowReferences(val resource: UriComponents, val position: Position, val locations: List<LanguageFeatures.Location>)

    /** The arguments of [SHOW_REFERENCES], or null if they are not a resource, a position and a list. */
    fun showReferences(arguments: JsonArray): ShowReferences? {
        val resource = UriComponents.from(arguments.getOrNull(0)) ?: return null
        val position = position(arguments.getOrNull(1)) ?: return null
        val locations = (arguments.getOrNull(2) as? JsonArray ?: return null).mapNotNull { element ->
            val location = element as? JsonObject ?: return@mapNotNull null
            val uri = UriComponents.from(location["uri"]) ?: return@mapNotNull null
            LanguageFeatures.Location(uri, range(location["range"]) ?: return@mapNotNull null)
        }
        return ShowReferences(resource, position, locations)
    }

    /**
     * `_workbench.open(resource, [column, options]?, label?)`: what `vscode.open` runs in the
     * workbench, and so what a tree item or link written with `vscode.open` arrives as.
     */
    const val OPEN = "_workbench.open"

    data class Open(val resource: UriComponents, val selection: Range?, val preserveFocus: Boolean)

    /** The arguments of [OPEN], or null without a resource; the resource may also be a URI string. */
    fun open(arguments: JsonArray): Open? {
        val resource = when (val first = arguments.getOrNull(0)) {
            // Any scheme: `UriComponents.of` takes only the ones documents are mirrored for.
            is JsonPrimitive -> first.contentOrNull?.let { text ->
                runCatching { java.net.URI(text) }.getOrNull()?.takeIf { it.scheme != null }?.let {
                    UriComponents(
                        it.scheme,
                        it.rawAuthority.orEmpty(),
                        it.path ?: it.schemeSpecificPart,
                        it.rawQuery.orEmpty(),
                        it.fragment.orEmpty()
                    )
                }
            }

            else -> UriComponents.from(first)
        } ?: return null
        val options = (arguments.getOrNull(1) as? JsonArray)?.getOrNull(1) as? JsonObject
        return Open(
            resource,
            range(options?.get("selection")),
            (options?.get("preserveFocus") as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /**
     * `_workbench.diff(left, right, label?, [column, options]?)`: what `vscode.diff` runs in the
     * workbench -- GitLens' compare actions, with `gitlens:` resources its content provider serves.
     */
    const val DIFF = "_workbench.diff"

    data class Diff(val left: UriComponents, val right: UriComponents, val title: String?)

    /** The arguments of [DIFF], or null without both resources. */
    fun diff(arguments: JsonArray): Diff? {
        val left = UriComponents.from(arguments.getOrNull(0)) ?: return null
        val right = UriComponents.from(arguments.getOrNull(1)) ?: return null
        return Diff(left, right, (arguments.getOrNull(2) as? JsonPrimitive)?.contentOrNull)
    }

    /**
     * `_workbench.changes(title, [[label, original?, modified?], ...])`: what `vscode.changes` runs,
     * VS Code's multi-file diff. A missing side is a file added or deleted.
     */
    const val CHANGES = "_workbench.changes"

    data class Change(val label: UriComponents, val original: UriComponents?, val modified: UriComponents?)

    data class Changes(val title: String?, val changes: List<Change>)

    /** The arguments of [CHANGES], or null without a list of changes. */
    fun changes(arguments: JsonArray): Changes? {
        val changes = (arguments.getOrNull(1) as? JsonArray ?: return null).mapNotNull { element ->
            val change = element as? JsonArray ?: return@mapNotNull null
            val label = UriComponents.from(change.getOrNull(0)) ?: return@mapNotNull null
            Change(label, UriComponents.from(change.getOrNull(1)), UriComponents.from(change.getOrNull(2)))
        }
        return Changes((arguments.getOrNull(0) as? JsonPrimitive)?.contentOrNull, changes)
    }

    /**
     * `_executeDocumentSymbolProvider(resource)`: what `vscode.executeDocumentSymbolProvider` runs in
     * the workbench. The answer is the outline as the workbench's own `DocumentSymbol`s, the shape
     * [DocumentSymbols.toJson] writes, which the host turns into the API's symbols.
     */
    const val EXECUTE_DOCUMENT_SYMBOLS = "_executeDocumentSymbolProvider"

    /** The resource a command such as [EXECUTE_DOCUMENT_SYMBOLS] takes first, or null without one. */
    fun resource(arguments: JsonArray): UriComponents? = UriComponents.from(arguments.getOrNull(0))

    /** Moves the active editor into a window of its own; it takes no arguments. */
    const val MOVE_EDITOR_TO_NEW_WINDOW = "workbench.action.moveEditorToNewWindow"

    /**
     * `git.publish`: VS Code's Git extension pushing the current branch to a remote, which GitLens
     * runs for Publish Branch. That extension is VS Code's own and not hosted here.
     */
    const val GIT_PUBLISH = "git.publish"

    /**
     * The commands behind `vscode.execute…Provider` that take a resource and a position, which the
     * host has converted to the editor model's 1-based one. Each answers as the workbench does: what
     * the providers for the resource gave, which the host turns into the API's types.
     */
    const val EXECUTE_DEFINITIONS = "_executeDefinitionProvider"
    const val EXECUTE_TYPE_DEFINITIONS = "_executeTypeDefinitionProvider"
    const val EXECUTE_IMPLEMENTATIONS = "_executeImplementationProvider"
    const val EXECUTE_REFERENCES = "_executeReferenceProvider"
    const val EXECUTE_HOVER = "_executeHoverProvider"

    /** `_executeWorkspaceSymbolProvider(query)`: what `vscode.executeWorkspaceSymbolProvider` runs. */
    const val EXECUTE_WORKSPACE_SYMBOLS = "_executeWorkspaceSymbolProvider"

    /** The resource and position of a command such as [EXECUTE_DEFINITIONS], or null without both. */
    fun resourceAt(arguments: JsonArray): Pair<UriComponents, Position>? {
        val resource = UriComponents.from(arguments.getOrNull(0)) ?: return null
        return resource to (position(arguments.getOrNull(1)) ?: return null)
    }

    /** The query of [EXECUTE_WORKSPACE_SYMBOLS]. */
    fun query(arguments: JsonArray): String? = (arguments.getOrNull(0) as? JsonPrimitive)?.contentOrNull

    /**
     * Definition links as the workbench's `LocationLink`s. The host only takes one for a link -- with
     * its selection ranges -- when its `uri` is revived as a URI, which takes the marshalling id.
     */
    fun locationLinks(links: List<LanguageFeatures.DefinitionLink>): String = JsonArray(links.map { link ->
        buildJsonObject {
            put("uri", uri(link.resource))
            put("range", link.target.toJson())
            link.selection?.let { put("targetSelectionRange", it.toJson()) }
            link.origin?.let { put("originSelectionRange", it.toJson()) }
        }
    }).toString()

    /** Locations as the workbench's `Location`s. */
    fun locations(locations: List<LanguageFeatures.Location>): String = JsonArray(locations.map { location ->
        buildJsonObject {
            put("uri", uri(location.resource))
            put("range", location.range.toJson())
        }
    }).toString()

    /** A hover's markdown as the workbench's `Hover`s: none, or one with it as its only part. */
    fun hovers(markdown: String?): String = JsonArray(listOfNotNull(markdown?.let { text ->
        buildJsonObject { put("contents", buildJsonArray { add(buildJsonObject { put("value", text) }) }) }
    })).toString()

    /** Workspace symbols as the workbench's `IWorkspaceSymbol`s. */
    fun workspaceSymbols(symbols: List<WorkspaceSymbols.Symbol>): String = JsonArray(symbols.map { symbol ->
        buildJsonObject {
            put("name", symbol.name)
            symbol.containerName?.let { put("containerName", it) }
            put("kind", symbol.kind)
            put("tags", buildJsonArray { if (symbol.deprecated) add(1) })
            put("location", buildJsonObject {
                put("uri", uri(symbol.resource))
                put("range", symbol.range.toJson())
            })
        }
    }).toString()

    private fun uri(resource: UriComponents): JsonObject =
        JsonObject(resource.toJson() + (MARSHALLED_ID to JsonPrimitive(1)))

    private const val MARSHALLED_ID = $$"$mid"

    /**
     * The settings editor's commands. VS Code shows its Settings UI, filtered by a query; settings
     * here are a file, so these open that file instead.
     */
    val OPEN_SETTINGS = setOf(
        "workbench.action.openSettings",
        "workbench.action.openSettings2",
        "workbench.action.openGlobalSettings",
        "workbench.action.openSettingsJson",
        "workbench.action.openApplicationSettingsJson",
    )

    /**
     * The setting names the query of an [OPEN_SETTINGS] command asks for, without its filters such
     * as `@ext:publisher.name`. The query is the first argument, as a string or as `{query}`.
     */
    fun settingsQuery(arguments: JsonArray): List<String> {
        val query = when (val first = arguments.getOrNull(0)) {
            is JsonPrimitive -> first.contentOrNull
            is JsonObject -> (first["query"] as? JsonPrimitive)?.contentOrNull
            else -> null
        } ?: return emptyList()
        return query.split(' ').filter { it.isNotBlank() && !it.startsWith("@") }
    }

    private fun position(element: JsonElement?): Position? {
        val json = element as? JsonObject ?: return null
        json.int("lineNumber")?.let { line -> return Position(line, json.int("column") ?: 1) }
        val line = json.int("line") ?: return null
        return Position(line + 1, (json.int("character") ?: 0) + 1)
    }

    private fun range(element: JsonElement?): Range? {
        Range.from(element)?.let { return it }
        // An LSP range, or the `[start, end]` a vscode.Range serialises to unconverted.
        val (start, end) = when (element) {
            is JsonObject -> position(element["start"]) to position(element["end"])
            is JsonArray -> position(element.getOrNull(0)) to position(element.getOrNull(1))
            else -> return null
        }
        if (start == null || end == null) return null
        return Range(start.lineNumber, start.column, end.lineNumber, end.column)
    }

    private fun JsonObject.int(key: String): Int? = runCatching { get(key)?.jsonPrimitive?.intOrNull }.getOrNull()
}
