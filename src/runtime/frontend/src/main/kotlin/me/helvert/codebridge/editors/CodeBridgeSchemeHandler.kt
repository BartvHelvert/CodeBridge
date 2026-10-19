package me.helvert.codebridge.editors

import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.isLinux
import com.intellij.platform.eel.isWindows
import com.intellij.platform.eel.provider.localEel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Makes this machine hand links under the editor's own scheme -- `vscodium://...` -- to
 * [AppLinkLauncher], which passes them to the running IDE.
 *
 * Some sign-in pages offer to return to the editor only under a scheme they know: gitkraken.dev
 * shows "Return to GitLens" for `vscode:` and `vscodium:`, and only the code to paste for an
 * address on the IDE's built-in server. The bundled runtime is VSCodium's extension host, which
 * registers nothing, so the scheme is usually free.
 *
 * Never taken from someone else: when the scheme already opens another program -- VSCodium
 * installed later, say -- it is left alone, and links go out on the built-in server instead. Nor
 * VS Code's own scheme, when the user points CodeBridge at a VS Code installation.
 *
 * Windows and Linux. Not macOS, where a scheme is declared by an application bundle and its link
 * arrives as an Apple event rather than an argument; links go out on the built-in server there.
 */
@Suppress("LoggingSimilarMessage") // Registering on Windows and elsewhere logs the same outcomes.
internal object CodeBridgeSchemeHandler {

    private val LOG = Logger.getInstance(CodeBridgeSchemeHandler::class.java)

    private val LAUNCHER: String = AppLinkLauncher::class.java.name

    /** VS Code's, which its installer registers; never claimed, whatever the registry says now. */
    private val NEVER = setOf("vscode", "vscode-insiders")

    /**
     * Has this machine hand [scheme] to the launcher, which uses [port] for a link that names none.
     * Whether it does now. Run off the editor thread: it starts processes.
     */
    fun register(scheme: String, port: Int): Boolean {
        if (scheme in NEVER || !scheme.matches(Regex("[a-z][a-z0-9+.-]*"))) return false
        return try {
            when {
                localEel.platform.isWindows -> windows(scheme, port)
                localEel.platform.isLinux -> linux(scheme, port)
                else -> false
            }
        } catch (failure: Exception) {
            LOG.warn("Could not register the $scheme: scheme", failure)
            false
        }
    }

    /**
     * What the browser names when it asks to open a link -- "Open IntelliJ IDEA?" -- rather than the
     * program that runs, the IDE's Java runtime ("OpenJDK Platform binary").
     */
    private val name: String get() = ApplicationNamesInfo.getInstance().fullProductName

    private fun launcher(port: Int, link: String): List<String> {
        val java =
            Path.of(System.getProperty("java.home"), "bin", if (localEel.platform.isWindows) "javaw.exe" else "java")
        val classes = PathManager.getJarPathForClass(AppLinkLauncher::class.java)
            ?: error("Cannot find where $LAUNCHER is loaded from")
        return listOf(java.toString(), "-cp", classes, LAUNCHER, link, port.toString())
    }

    // Windows: HKEY_CURRENT_USER\Software\Classes\<scheme>, as an installer registers one for its user.

    private fun windows(scheme: String, port: Int): Boolean {
        val key = """Software\Classes\$scheme"""
        val ours = launcher(port, "%1").joinToString(" ") { "\"$it\"" }
        val user = registryValue("""HKCU\$key\shell\open\command""")
        val machine = registryValue("""HKLM\$key\shell\open\command""")
        val foreign = listOfNotNull(user, machine).firstOrNull { LAUNCHER !in it }
        if (foreign != null) {
            LOG.info("Not registering $scheme: links, which open $foreign")
            return false
        }
        if (user == ours && registryValue("""HKCU\$key\shell\open""", "FriendlyAppName") == name) return true
        // A .reg file rather than `reg add` arguments: the command is quoted inside, and Windows'
        // argument quoting gets nested quotes wrong.
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val text = """
            |Windows Registry Editor Version 5.00
            |
            |[HKEY_CURRENT_USER\$key]
            |@=${quote("URL:$scheme")}
            |"URL Protocol"=""
            |
            |[HKEY_CURRENT_USER\$key\shell\open]
            |"FriendlyAppName"=${quote(name)}
            |
            |[HKEY_CURRENT_USER\$key\shell\open\command]
            |@=${quote(ours)}
            |""".trimMargin().replace("\n", "\r\n")
        val file = Files.createTempFile("codebridge-", ".reg")
        try {
            // `reg import` reads UTF-16 with a byte-order mark.
            Files.write(file, byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(StandardCharsets.UTF_16LE))
            val imported = run("reg", "import", file.toString()) != null
            if (imported) LOG.info("Registered $scheme: links to CodeBridge")
            return imported
        } finally {
            file.deleteIfExists()
        }
    }

    /** Value [name] of registry [key] -- its default value when null -- or null when it has none. */
    private fun registryValue(key: String, name: String? = null): String? {
        val output =
            (if (name == null) run("reg", "query", key, "/ve") else run("reg", "query", key, "/v", name)) ?: return null
        // `    (Default)    REG_SZ    <value>`
        return output.lineSequence().firstNotNullOfOrNull { line ->
            Regex("""^\s+\S.*?\s+REG_(?:EXPAND_)?SZ\s+(.*)$""").find(line)?.groupValues?.get(1)?.trim()
        }?.takeIf { it.isNotEmpty() }
    }

    // Linux: a hidden desktop entry, made the scheme's default with xdg-mime.

    private fun linux(scheme: String, port: Int): Boolean {
        val entry = "codebridge-$scheme.desktop"
        val current = run("xdg-mime", "query", "default", "x-scheme-handler/$scheme")?.trim().orEmpty()
        if (current.isNotEmpty() && current != entry) {
            LOG.info("Not registering $scheme: links, which open $current")
            return false
        }
        val applications = Path.of(
            System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"),
            "applications"
        )
        Files.createDirectories(applications)
        // The desktop entry spec's quoting: double quotes, with `"`, `` ` ``, `$` and `\` escaped inside.
        fun quote(text: String) = "\"" + text.replace(Regex("""(["`$\\])"""), """\\$1""") + "\""
        val exec = launcher(port, "%u").joinToString(" ") { if (it == "%u") it else quote(it) }
        val file = applications.resolve(entry)
        val content = """
            |[Desktop Entry]
            |Type=Application
            |Name=$name
            |Comment=Opens $scheme: links in the IDE running CodeBridge
            |Exec=$exec
            |NoDisplay=true
            |MimeType=x-scheme-handler/$scheme;
            |""".trimMargin()
        if (current == entry && Files.isRegularFile(file) && Files.readString(file) == content) return true
        file.writeText(content)
        val registered = run("xdg-mime", "default", entry, "x-scheme-handler/$scheme") != null
        if (registered) LOG.info("Registered $scheme: links to CodeBridge")
        return registered
    }

    /** Runs [command], returning its output, or null when it fails or is not there. */
    private fun run(vararg command: String): String? = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
    } catch (_: java.io.IOException) {
        null
    }
}
