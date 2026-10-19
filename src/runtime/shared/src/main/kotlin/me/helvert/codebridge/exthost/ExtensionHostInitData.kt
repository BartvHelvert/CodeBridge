package me.helvert.codebridge.exthost

import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.manifest.VsixManifest
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import java.nio.file.Path
import java.time.Instant

/**
 * Builds the payload the extension host waits for after it reports `Ready`.
 *
 * Several fields look optional and are not. `consoleForward` in particular must be present: without
 * it the console forwarder throws while services are being constructed, after which no `ExtHost*`
 * actor is ever registered and every call into the host comes back as `Unknown actor ...`, which
 * points nowhere near the real cause.
 */
object ExtensionHostInitData {

    fun build(
        installation: VsCodeInstallation,
        storageRoot: Path,
        extensions: List<JsonObject>,
        parentPid: Long,
        globalStorageHome: Path = storageRoot.resolve("globalStorage"),
        workspaceStorageHome: Path = storageRoot.resolve("workspaceStorage"),
    ): JsonObject = buildJsonObject {
        put("version", installation.version)
        put("quality", "stable")
        put("commit", installation.commit)
        put("parentPid", parentPid)

        putJsonObject("environment") {
            put("isExtensionDevelopmentDebug", false)
            put("appRoot", fileUri(installation.appDirectory))
            put("appName", installation.nameLong)
            put("appHost", "desktop")
            put("appUriScheme", installation.uriScheme)
            put("appLanguage", "en")
            put("extensionTelemetryLogResource", fileUri(storageRoot.resolve("logs/telemetry.log")))
            put("isExtensionTelemetryLoggingOnly", false)
            put("isPortable", false)
            put("globalStorageHome", fileUri(globalStorageHome))
            put("workspaceStorageHome", fileUri(workspaceStorageHome))
            put("useHostProxy", false)
            put("skipWorkspaceStorageLock", false)
            put("isSessionsWindow", false)
        }

        putJsonObject("extensions") {
            put("versionId", 1)
            put("allExtensions", JsonArray(extensions))
            putJsonArray("myExtensions") {
                extensions.forEach { add(it["identifier"] ?: JsonNull) }
            }
            putJsonObject("activationEvents") {
                extensions.forEach { extension ->
                    val id = identifierOf(extension) ?: return@forEach
                    putJsonArray(id) {
                        (extension["activationEvents"] as? JsonArray)?.forEach { add(it) }
                    }
                }
            }
        }

        putJsonObject("telemetryInfo") {
            put("sessionId", "codebridge")
            put("machineId", "codebridge")
            put("sqmId", "")
            put("devDeviceId", "")
            put("firstSessionDate", Instant.now().toString())
            put("msftInternal", false)
        }

        put("logLevel", 2)
        put("loggers", JsonArray(emptyList()))
        put("logsLocation", fileUri(storageRoot.resolve("logs")))
        put("autoStart", true)

        putJsonObject("remote") {
            put("isRemote", false)
            put("connectionData", JsonNull)
        }
        put("uiKind", 1)

        // Required; see the note on this object.
        putJsonObject("consoleForward") {
            put("includeStack", false)
            put("logNative", true)
        }
    }

    /**
     * An `ExtensionIdentifier` as the host keys them.
     *
     * `_lower` is not decoration. The host looks extensions up with
     * `toKey(id) => typeof id === "string" ? id.toLowerCase() : id._lower`, so an identifier built
     * from `value` alone keys to `undefined` and every lookup misses -- silently, because
     * `$activate` answers `false` for an unknown extension rather than raising.
     */
    fun identifier(id: String): JsonObject = buildJsonObject {
        put("value", id)
        put("_lower", id.lowercase())
    }

    /**
     * Renders a [VsixManifest] as the `IExtensionDescription` the host expects.
     *
     * The manifest is spread in wholesale because the host reads `contributes`, `engines`, `main`
     * and other npm fields straight off this object; only the identity and location fields are ours
     * to add.
     */
    fun describeExtension(manifest: VsixManifest, location: Path): JsonObject = buildJsonObject {
        manifest.raw.forEach { (key, value) -> put(key, value) }
        put("identifier", identifier(manifest.id))
        put("extensionLocation", fileUri(location))
        put("isBuiltin", false)
        put("isUserBuiltin", false)
        put("isUnderDevelopment", false)
        put("targetPlatform", "undefined")
        put("preRelease", false)
    }

    /** Empty configuration, enough to open the `ExtHostConfiguration` barrier. */
    fun emptyConfiguration(): JsonObject = buildJsonObject {
        listOf("defaults", "policy", "application", "userLocal", "userRemote", "workspace").forEach { scope ->
            putJsonObject(scope) {
                put("contents", buildJsonObject { })
                put("keys", JsonArray(emptyList()))
                put("overrides", JsonArray(emptyList()))
            }
        }
        put("folders", JsonArray(emptyList()))
        put("configurationScopes", JsonArray(emptyList()))
    }

    private fun identifierOf(extension: JsonObject): String? =
        ((extension["identifier"] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

    /**
     * VS Code revives URIs from these fields, so a plain object with them is enough.
     *
     * The init payload is full of resources, which is why this shorthand stays here; the spelling
     * itself belongs to the protocol and lives on [UriComponents].
     */
    fun fileUri(path: Path): JsonObject = UriComponents.of(path).toJson()

    /**
     * VS Code's `IStaticWorkspaceData` for [layout]: its folders, and for a multi-root workspace the
     * `.code-workspace` file as its `configuration`, which `workspace.workspaceFile` reads.
     */
    fun workspaceData(id: String, layout: CodeBridgeWorkspace): JsonObject = buildJsonObject {
        put("id", id); put("name", layout.name)
        layout.file?.let { put("configuration", fileUri(it)); put("isUntitled", false) }
        put("folders", buildJsonArray {
            layout.folders.forEach { folder ->
                add(buildJsonObject {
                    put("uri", fileUri(folder.path)); put("name", folder.name); put(
                    "index",
                    folder.index
                )
                })
            }
        })
    }
}
