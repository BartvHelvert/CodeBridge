import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * CodeBridge Runtime: the extension host, pinned and shipped per platform, and everything that draws
 * what extensions do. A plugin of its own, so a plugin that bundles a VS Code extension can depend on
 * it without the marketplace or VS Code's workspace files coming along; the root project, CodeBridge,
 * adds those.
 *
 * No code of its own, only the descriptor; the modules bring the Kotlin.
 */
plugins {
    java
    id("org.jetbrains.intellij.platform")
}

sourceSets.main {
    resources.setSrcDirs(listOf("resources"))
}

/**
 * Whether the plugin ships `CodeBridge.internal.backend`, everything that needs the platform's internal API.
 * On by default; `-PinternalApi=false` builds what can be published past the Plugin Verifier's
 * internal API check, without those features, and drops the module from the descriptor too, since a
 * listed module missing from the plugin stops it loading.
 */
val withInternalApi = providers.gradleProperty("internalApi").map(String::toBoolean).getOrElse(true)

/**
 * Whether the distribution carries the index the IDE's settings search reads, which
 * `buildSearchableOptions` makes by starting an IDE. On by default; `-PsearchableOptions=false`
 * skips it for a build nobody searches settings in, as the UI tests' CI job is: CodeBridge installs
 * this plugin as a built distribution, so the tests paid for that IDE start too.
 */
val withSearchableOptions = providers.gradleProperty("searchableOptions").map(String::toBoolean).getOrElse(true)

// The descriptor reaches the jar from this task's output, not from processResources, so it is
// edited here once patched.
tasks.patchPluginXml {
    inputs.property("internalApi", withInternalApi)
    val internalApi = withInternalApi
    doLast {
        if (internalApi) return@doLast
        val descriptor = outputFile.get().asFile
        descriptor.writeText(descriptor.readLines().filterNot { "\"CodeBridge.internal.backend\"" in it }
            .joinToString("\n"))
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.idea.get())

        pluginModule(implementation(project(":protocol")))
        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))
        pluginModule(implementation(project(":textmate.backend")))
        pluginModule(implementation(project(":terminal.frontend")))
        pluginModule(implementation(project(":testrunner.backend")))
        if (withInternalApi) pluginModule(implementation(project(":internal.backend")))
        pluginModule(implementation(project(":webview.frontend")))
    }
}

intellijPlatform {
    // The distribution's name: `CodeBridgeRuntime-<version>-<os>-<arch>.zip`.
    projectName = "CodeBridgeRuntime"
    buildSearchableOptions = withSearchableOptions

    pluginVerification {
        ides {
            current()
        }
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
    }
}

/**
 * Internal API usages the plugin is allowed, matched against the Plugin Verifier's report lines.
 *
 * The verifier can only fail on all of them or none: its ignore file covers compatibility problems,
 * not API usages. So it reports them, and this fails `verifyPlugin` on any not listed here.
 */
val allowedInternalApiUsages = listOf(
    // The module that exists to use internal API, left out with `-PinternalApi=false`.
    Regex(""" in (class |interface )?me\.helvert\.codebridge\.internal\."""),
    // A false positive: `reportRawProgress` is public, and inlines its internal handle into the
    // caller (MP-7133). `withRawProgress` keeps that to one place.
    Regex("""RawProgressReporterHandle\b.* in me\.helvert\.codebridge\.progress\.RawProgressKt\."""),
)

tasks.verifyPlugin {
    val reports = verificationReportsDirectory
    val allowed = allowedInternalApiUsages
    doLast {
        val unexpected = reports.get().asFile.walk()
            .filter { it.name == "internal-api-usages.txt" }
            .flatMap { it.readLines() }
            .filter { line -> line.isNotBlank() && allowed.none { it.containsMatchIn(line) } }
            .toList()
        if (unexpected.isNotEmpty()) throw GradleException(
            "Internal API used outside the allowed usages in build.gradle.kts:\n" + unexpected.joinToString("\n")
        )
    }
}

/**
 * The extension host the plugin runs on: VSCodium's remote-extension-host package, pinned.
 *
 * Changing the version is a deliberate, tested upgrade -- extension-host rpcIds drift between builds
 * and a mismatch misroutes calls silently -- and changing it means replacing every hash below with
 * the `.sha256` published beside each asset. The hashes are pinned here rather than fetched with the
 * archive, so a replaced release asset fails the build instead of shipping.
 */
val vscodiumVersion = "1.135.06055"
val vscodiumPackages = mapOf(
    "win32-x64" to "3f7d84ba5b4440e4e328dad4fe182f14bba7333db170485e989ac2c1c49b6a0c",
    "linux-x64" to "bd23015a35b915bac3c6fca962ca5db427f5c8f049702e48ddeb72757ab32745",
    "linux-arm64" to "697d2cf622152b3b3affbbd18d48e5ff51e1e2fbf5833ad66262bda413c599c5",
    "darwin-x64" to "dc80d0c01f870c0c2c4d26469ce3c1ce80dd449b227cd8aee88af4fceb0e7453",
    "darwin-arm64" to "f645669f423f88fd2626d88f80d3f931b6c21fda93df4177d19a91c46815be1c",
)

/**
 * The same VSCodium build's web server package, for the built-in extensions VS Code's workbench
 * loads rather than its extension host: the ones that name languages, `java` for `.java` among
 * them. The REH package leaves them out, having no editor of its own; the web package carries them
 * for the browser's. Identical on every platform, so one platform's package serves all.
 */
val vscodiumWebPackage = "linux-x64" to "e36cf545d526e7f2e10679e9b35a269e9ba573f9527038bf39bc26aab64d27a6"

/** Fetches one file and refuses it unless it has the expected SHA-256. */
@DisableCachingByDefault(because = "A download; keeping a second copy in the build cache gains nothing")
abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val archive: RegularFileProperty

    @TaskAction
    fun download() {
        val target = archive.get().asFile
        // Gradle reruns this whenever the build script changes, since the task class lives in it.
        // Hashing a file already here is far cheaper than fetching it again.
        if (target.isFile && copyHashing(target.inputStream(), OutputStream.nullOutputStream()) == sha256.get()) return
        val partial = File(target.path + ".part")
        target.parentFile.mkdirs()
        val actual = copyHashing(URI(url.get()).toURL().openStream(), partial.outputStream())
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException("Checksum mismatch for ${url.get()}: expected ${sha256.get()} but got $actual")
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Copies [input] to [output], closing both, and returns the SHA-256 of what passed through. */
    private fun copyHashing(input: InputStream, output: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source -> DigestOutputStream(output, digest).use { source.copyTo(it) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Downloads go under the project's `.gradle`, not `build`, so `clean` does not cost five 113 MB
 * fetches. Version-qualified, so a bumped pin cannot be confused with the previous one.
 */
val vscodiumDownloads = rootProject.layout.projectDirectory.dir(".gradle/vscodium/$vscodiumVersion")

val downloadRuntime = vscodiumPackages.mapValues { (platform, hash) ->
    tasks.register<DownloadVerified>("downloadRuntime_$platform") {
        group = "distribution"
        description = "Downloads the VSCodium $vscodiumVersion extension host for $platform."
        val asset = "vscodium-reh-$platform-$vscodiumVersion.tar.gz"
        url = "https://github.com/VSCodium/vscodium/releases/download/$vscodiumVersion/$asset"
        sha256 = hash
        archive = vscodiumDownloads.file(asset)
    }
}

val downloadWorkbenchExtensions = tasks.register<DownloadVerified>("downloadWorkbenchExtensions") {
    group = "distribution"
    description = "Downloads the VSCodium $vscodiumVersion web server, for its workbench's built-in extensions."
    val (platform, hash) = vscodiumWebPackage
    val asset = "vscodium-reh-web-$platform-$vscodiumVersion.tar.gz"
    url = "https://github.com/VSCodium/vscodium/releases/download/$vscodiumVersion/$asset"
    sha256 = hash
    archive = vscodiumDownloads.file(asset)
}

/**
 * The licences of what the runtime is made of, which its archive does not carry: VS Code's own and
 * its third-party notices, VSCodium's, and Node's for `node`. The bundled `node_modules` and built-in
 * extensions carry their own. Pinned to the runtime's sources -- VS Code at the tag VSCodium's
 * `upstream/stable.json` names, Node at the `target` of that tag's `remote/.npmrc` -- so they change
 * with [vscodiumVersion].
 */
val runtimeLicenses = mapOf(
    "vscode-LICENSE.txt" to ("https://raw.githubusercontent.com/microsoft/vscode/1.135.0/LICENSE.txt" to
            "9480271317925265e806a9a196aaa33410a962fa9d4d1e248a4a5187bc8c9df9"),
    "vscode-ThirdPartyNotices.txt" to ("https://raw.githubusercontent.com/microsoft/vscode/1.135.0/ThirdPartyNotices.txt" to
            "4cb210d0c8f8fa304b37a31d44436ebf86dff59466a4033fbcd0c401d63c6568"),
    "vscodium-LICENSE.txt" to ("https://raw.githubusercontent.com/VSCodium/vscodium/$vscodiumVersion/LICENSE" to
            "2fa3f8948a0a17ea30b62845caf1ee8aae8b55b5417273920ca2df2642209e0e"),
    "node-LICENSE.txt" to ("https://raw.githubusercontent.com/nodejs/node/v24.18.1/LICENSE" to
            "148eacf7863ef4329224a29398623077200a27194aa075569faf4a0a85566ca5"),
)

val downloadRuntimeLicenses = runtimeLicenses.map { (name, source) ->
    tasks.register<DownloadVerified>("downloadRuntimeLicense_${name.substringBefore('.')}") {
        group = "distribution"
        description = "Downloads $name for the bundled extension host."
        url = source.first
        sha256 = source.second
        archive = vscodiumDownloads.file("licenses/$name")
    }
}

/**
 * The manifests of the workbench's built-in extensions, as `workbench-extensions/<name>/package.json`
 * beside the runtime's own `extensions/`. Manifests alone: `BuiltinLanguages` reads the languages
 * they contribute, and nothing runs them. Kept apart from `extensions/` so the host loads exactly
 * the built-ins it always has.
 */
fun CopySpec.workbenchExtensions() {
    from(tarTree(downloadWorkbenchExtensions.flatMap { it.archive })) {
        include("extensions/*/package.json")
        // `extensions/` renamed, wherever an enclosing `into` put it.
        eachFile {
            val segments = relativePath.segments.toMutableList()
            segments[segments.indexOf("extensions")] = "workbench-extensions"
            relativePath = RelativePath(true, *segments.toTypedArray())
        }
        includeEmptyDirs = false
    }
}

/**
 * The runtime moved under `runtime/`, still as a tar.
 *
 * VSCodium's archive has no top-level directory, and a variant's files land at the plugin root as
 * they are laid out, so they need moving. Extracting to disk would do that, and on a Windows build
 * machine it would also drop the executable bit from `node`, `spawn-helper`, `rg` and the rest:
 * the plugin zip records whatever the files on disk say, and the IDE restores exactly that on
 * install. Copied archive to archive, each entry keeps the mode the tar gave it -- provided the
 * archive task is told to keep it; see [keepsExecutableBits].
 */
fun bundledRuntime(platform: String): FileTree {
    val repack = tasks.register<Tar>("repackRuntime_$platform") {
        description = "Moves the $platform extension host under runtime/ for its plugin variant."
        from(tarTree(downloadRuntime.getValue(platform).flatMap { it.archive })) {
            into("runtime")
        }
        into("runtime") { workbenchExtensions() }
        into("runtime/licenses") { from(downloadRuntimeLicenses.map { it.flatMap { task -> task.archive } }) }
        archiveFileName = "vscodium-reh-$platform-$vscodiumVersion.tar"
        destinationDirectory = rootProject.layout.buildDirectory.dir("vscodium")
        // Already an archive of an archive: compressing it again would cost time on every build.
        compression = Compression.NONE
        keepsExecutableBits()
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    return tarTree(repack.flatMap { it.archiveFile })
}

/**
 * Gradle 9 archive tasks normalize every file to `644` unless told otherwise, which ships a `node`
 * that cannot be started on macOS or Linux -- and does so silently, since nothing in the build runs
 * it. Despite the name, the modes come from the source entries, so the tar's own bits survive even
 * on a Windows build machine.
 */
fun AbstractArchiveTask.keepsExecutableBits() = useFileSystemPermissions()

tasks.withType<BuildPluginTask>().matching { it.name.startsWith("buildPluginVariants_") }.configureEach {
    keepsExecutableBits()
}

/** The plugin's licence and the third-party notices it is distributed under, at its root. */
tasks.withType<BuildPluginTask>().configureEach {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
}

/**
 * One distribution per OS and architecture, each carrying that platform's extension host.
 *
 * Windows on ARM is built too -- the plugin cannot opt out of one of the six -- and ships without a
 * runtime, because VSCodium publishes none; the plugin reports that on its settings page.
 *
 * The runtime goes into the distributions only, not through `nativeVariants`: whatever is declared
 * there is also copied into every sandbox (`runIde`, the tests), and a sandbox is a `Sync` that
 * deletes the runtime and copies all ~2,800 files back out of the tar each time the plugin's code
 * changes -- close to half a minute on Windows. The sandboxes use the unpacked [hostRuntimeDirectory]
 * instead; see `usesHostRuntime` in the root build script.
 */
intellijPlatform {
    nativeVariants {
        enabled = true
    }
}
mapOf(
    "linux_x86_64" to "linux-x64",
    "linux_arm64" to "linux-arm64",
    "mac_x86_64" to "darwin-x64",
    "mac_arm64" to "darwin-arm64",
    "windows_x86_64" to "win32-x64",
).forEach { (variant, platform) ->
    // Outside the configuration action: the runtime registers a task, which Gradle forbids there.
    val runtime = bundledRuntime(platform)
    tasks.named<BuildPluginTask>("buildPluginVariants_$variant") {
        from(runtime)
    }
}

/**
 * The build machine's runtime, unpacked, for the tests.
 *
 * Neither a test JVM nor a sandbox has the runtime inside the plugin, so the test and `runIde` tasks
 * point `codebridge.runtime.dir` here instead. A `Sync` from the download, so it reruns only when the
 * pin does.
 */
val hostRuntimePlatform: String? = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val x64 = arch == "amd64" || arch == "x86_64"
    val arm64 = arch == "aarch64" || arch == "arm64"
    when {
        "windows" in os -> "win32-x64".takeIf { x64 }
        "mac" in os -> if (arm64) "darwin-arm64" else "darwin-x64".takeIf { x64 }
        else -> if (arm64) "linux-arm64" else "linux-x64".takeIf { x64 }
    }
}
val hostRuntimeDirectory = rootProject.layout.buildDirectory.dir("vscodium/host")
val extractHostRuntime = tasks.register<Sync>("extractHostRuntime") {
    group = "verification"
    description = "Unpacks the build machine's extension host for the tests that start one."
    hostRuntimePlatform?.let { platform ->
        from(tarTree(downloadRuntime.getValue(platform).flatMap { it.archive }))
        workbenchExtensions()
    }
    into(hostRuntimeDirectory)
}
