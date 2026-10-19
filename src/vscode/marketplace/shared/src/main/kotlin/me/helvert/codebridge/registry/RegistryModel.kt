package me.helvert.codebridge.registry

import kotlinx.serialization.Serializable

/**
 * The registry's own site. The API lives under `/api` on the same host, so both are derived from
 * this rather than each spelling out the host.
 */
const val OPEN_VSX: String = "https://open-vsx.org"

/** The registry's page for the extension `namespace.name`; see [RegistryExtension.pageUrl]. */
fun registryPageUrl(namespace: String, name: String): String = "$OPEN_VSX/extension/$namespace/$name"

/**
 * One extension as the registry lists it.
 *
 * Search results and the detail endpoint carry overlapping fields, so the summary is shared and
 * [RegistryDetail] adds what only the detail endpoint knows.
 */
@Serializable
data class RegistryExtension(
    val namespace: String,
    val name: String,
    val version: String,
    val displayName: String? = null,
    val description: String? = null,
    val downloadCount: Int = 0,
    val averageRating: Double? = null,
    val reviewCount: Int = 0,
    val verified: Boolean = false,
    val deprecated: Boolean = false,
    val preview: Boolean = false,
    val iconUrl: String? = null,
    val timestamp: String? = null,
) {
    /** The identifier VS Code uses, and the one the installed store keys on. */
    val id: String get() = "$namespace.$name"

    /**
     * This extension's page on the registry.
     *
     * Not the publisher's `homepage`, which is usually a GitHub repository. The IDE's own "plugin
     * homepage" link goes to the *marketplace* page for the plugin, and this is the equivalent.
     *
     * Both segments go into the path unescaped, as they do in the API calls: Open VSX restricts
     * namespace and name to characters that need no escaping.
     */
    val pageUrl: String get() = registryPageUrl(namespace, name)

    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

/** A page of search results. [total] is the whole result set, not the page. */
@Serializable
data class RegistrySearch(
    val extensions: List<RegistryExtension>,
    val offset: Int,
    val total: Int,
) {
    /** Whether asking for the next page would return anything. */
    val hasMore: Boolean get() = offset + extensions.size < total
}

/** Everything the detail endpoint adds, including where to actually get the VSIX. */
@Serializable
data class RegistryDetail(
    val extension: RegistryExtension,
    val license: String? = null,
    val repository: String? = null,
    val homepage: String? = null,
    val categories: List<String> = emptyList(),
    val engineRange: String? = null,
    val extensionKind: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    /** Target platform to VSIX URL, as published. May contain only `universal`. */
    val downloads: Map<String, String> = emptyMap(),
    /** `files.download`: the platform-neutral VSIX, when there is one. */
    val download: String? = null,
    /** `files.sha256`: lets a download be verified before it is installed. */
    val sha256: String? = null,
    val readme: String? = null,
) {

    /**
     * The VSIX to install on [targetPlatform], or `null` when nothing suitable is published.
     *
     * An exact platform build wins over the universal one: a platform-specific package exists
     * precisely because the extension ships a native binary, and installing the universal build in
     * its place produces an extension that activates and then fails at the moment it spawns
     * something. Falling back the other way is safe, because a universal build is all there is.
     */
    fun downloadFor(targetPlatform: String): String? =
        downloads[targetPlatform] ?: downloads[UNIVERSAL] ?: download

    /** Whether this extension publishes a build specifically for [targetPlatform]. */
    fun isPlatformSpecific(targetPlatform: String): Boolean = downloads.containsKey(targetPlatform)

    /** Platform builds on offer, universal excluded, for reporting when none matches. */
    fun platforms(): List<String> = downloads.keys.filterNot { it == UNIVERSAL }.sorted()

    companion object {
        const val UNIVERSAL: String = "universal"

        /**
         * The checksum published beside a VSIX.
         *
         * `files.sha256` only ever describes the universal package, so a platform build needs its
         * own. The registry publishes one next to every download under the same name, which is
         * cheaper than fetching per-platform detail just to learn a URL we can derive.
         */
        fun checksumUrl(downloadUrl: String): String? =
            if (downloadUrl.endsWith(".vsix")) downloadUrl.removeSuffix(".vsix") + ".sha256" else null
    }
}
