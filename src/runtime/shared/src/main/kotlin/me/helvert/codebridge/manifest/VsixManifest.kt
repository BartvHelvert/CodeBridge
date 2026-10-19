package me.helvert.codebridge.manifest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The `extension/package.json` of a VSIX: a normal npm manifest plus the fields VS Code adds.
 *
 * A VSIX is nothing more than a zip, and installing one is nothing more than unpacking it, so this
 * manifest is the only file that carries meaning for us.
 */
data class VsixManifest(
    val publisher: String,
    val name: String,
    val version: String,
    val displayName: String? = null,
    val description: String? = null,
    /** Path to the extension's icon, relative to the extension root. VS Code requires a PNG. */
    val icon: String? = null,
    /** `engines.vscode`, e.g. `^1.61.0` - the API version the extension expects to be handed. */
    val engineRange: String? = null,
    /** Entry point relative to the extension root. Absent means the extension is purely declarative. */
    val main: String? = null,
    val activationEvents: List<String> = emptyList(),
    val extensionDependencies: List<String> = emptyList(),
    val extensionPack: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val contributes: Contributes = Contributes(),
    val raw: JsonObject,
) {
    /** The identifier used everywhere in the VS Code ecosystem, e.g. `vadimcn.vscode-lldb`. */
    val id: String get() = "$publisher.$name"

    /** A manifest without `main` contributes only static data and can never need an extension host. */
    val isDeclarativeOnly: Boolean get() = main == null
}

/**
 * The `contributes` block.
 *
 * Only the contribution points we act on are modelled. [declaredKeys] records everything the
 * manifest actually declared, so that we can report what we silently ignored rather than leaving
 * the user to guess why part of their extension does nothing.
 */
data class Contributes(
    val debuggers: List<DebuggerContribution> = emptyList(),
    val languages: List<LanguageContribution> = emptyList(),
    /** Language ids for which the extension allows breakpoints to be set. */
    val breakpointLanguages: List<String> = emptyList(),
    val languageServers: List<LanguageServerContribution> = emptyList(),
    val declaredKeys: Set<String> = emptySet(),
) {
    /** Contribution points present in the manifest that we do not yet understand. */
    val ignoredKeys: Set<String> get() = declaredKeys - HANDLED_KEYS

    fun debugger(type: String): DebuggerContribution? = debuggers.firstOrNull { it.type == type }

    private companion object {
        val HANDLED_KEYS = setOf("debuggers", "languages", "breakpoints")
    }
}

/**
 * One entry of `contributes.debuggers`, describing a debug adapter and the shape of the
 * `launch.json` entries that drive it.
 */
data class DebuggerContribution(
    val type: String,
    val label: String? = null,
    /** Adapter entry point relative to the extension root, when the manifest declares one at all. */
    val program: String? = null,
    /** Interpreter for [program], typically `node`. Absent means [program] is directly executable. */
    val runtime: String? = null,
    val args: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    /**
     * Maps a `${command:id}` name usable in launch.json to the command the extension registers,
     * e.g. `pickProcess` to `lldb.pickProcess`. Resolving one requires running the extension.
     */
    val variables: Map<String, String> = emptyMap(),
    /**
     * JSON Schema per request kind (`launch`, `attach`), kept verbatim. It drives completion and
     * validation in launch.json; interpreting it here would mean reimplementing JSON Schema.
     */
    val configurationAttributes: JsonObject? = null,
    val initialConfigurations: JsonElement? = null,
    val configurationSnippets: JsonArray? = null,
    val raw: JsonObject,
) {
    fun attributesFor(request: String): JsonObject? = configurationAttributes?.get(request) as? JsonObject

    /** Request kinds this debugger supports, normally some of `launch` and `attach`. */
    val requestKinds: Set<String> get() = configurationAttributes?.keys.orEmpty()

    /**
     * Whether the adapter can be started straight from the manifest, or only by running the
     * extension's JavaScript.
     *
     * This is the single most important question about a debug extension, because it decides
     * whether supporting it needs an extension host at all.
     */
    val adapterDiscovery: AdapterDiscovery
        get() = when {
            program != null -> AdapterDiscovery.Declared(program, runtime, args)
            else -> AdapterDiscovery.RequiresExtensionHost(
                "the manifest declares no \"program\", so the adapter can only come from a " +
                    "DebugAdapterDescriptorFactory registered by the extension at runtime"
            )
        }
}

/** How the debug adapter for a [DebuggerContribution] can be obtained. */
sealed interface AdapterDiscovery {

    /** The manifest names the adapter outright; it can be spawned without running any JavaScript. */
    data class Declared(
        val program: String,
        val runtime: String?,
        val args: List<String>,
    ) : AdapterDiscovery

    /** Only the extension's own code can produce the adapter. */
    data class RequiresExtensionHost(val reason: String) : AdapterDiscovery
}

/** One entry of `contributes.languages`. */
data class LanguageContribution(
    val id: String,
    val aliases: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val filenames: List<String> = emptyList(),
    /** Globs for file names, as `*.code-workspace` or `Dockerfile.*`; see `LanguageMatching`. */
    val filenamePatterns: List<String> = emptyList(),
    /** Path to a `language-configuration.json`: comment markers, brackets, auto-closing pairs. */
    val configuration: String? = null,
    /** What the language's files should look like in a file tree, where the extension says. */
    val icon: LanguageIcon? = null,
)

/**
 * `contributes.languages[].icon`: one image per theme, as paths inside the extension.
 *
 * VS Code requires both, and so does this -- an entry naming only one is read as naming neither,
 * because half a pair is as likely to be a typo as an intention. They are untrusted paths out of
 * the package's own manifest and must be resolved against the extension directory before use.
 */
data class LanguageIcon(val light: String, val dark: String)

/** Direct language-server declarations used by extensions that expose an executable server. */
data class LanguageServerContribution(
    val command: String,
    val args: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val raw: JsonObject,
)
