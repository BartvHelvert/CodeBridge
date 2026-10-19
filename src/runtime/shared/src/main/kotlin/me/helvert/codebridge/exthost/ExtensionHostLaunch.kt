package me.helvert.codebridge.exthost

import java.nio.file.Path

/**
 * How to start VS Code's extension host as a child process.
 *
 * Two things about this are not obvious and were established by running it:
 *
 * 1. **Which Node runs it depends on where it came from.** In a desktop install,
 *    `resources/app/node_modules` contains only native modules; everything else lives in
 *    `node_modules.asar`, and asar support is a patch Electron applies to `fs`, so plain Node
 *    cannot resolve the host's dependencies -- hence [Runtime.ELECTRON] and `ELECTRON_RUN_AS_NODE`.
 *    A remote-extension-host package has no asar and ships its own `node`, so it needs neither.
 * 2. **NLS must be configured**, or startup aborts with a missing-message error before the host
 *    ever connects.
 */
data class ExtensionHostLaunchSpec(
    /** The Node runtime to launch: VS Code's Electron binary, or a plain `node`. */
    val executable: Path,
    /** The directory holding `out/`, `product.json` and `node_modules`. */
    val appDirectory: Path,
    /** Pipe we are already listening on; the host connects to it. */
    val pipeName: String,
    /** The host polls this pid and exits when it disappears, so it cannot outlive us. */
    val parentPid: Long,
    /** What [executable] is, which decides whether Electron needs talking out of being an editor. */
    val runtime: Runtime = Runtime.ELECTRON,
    val locale: String = "en",
    val preloadScript: Path? = null,
) {

    /** What kind of Node [executable] is. */
    enum class Runtime {
        /** VS Code's own Electron binary; its dependencies live in `node_modules.asar`. */
        ELECTRON,

        /** A plain `node`, as shipped inside a remote-extension-host package. */
        NODE,
    }

    val bootstrapScript: Path get() = appDirectory.resolve("out").resolve("bootstrap-fork.js")

    val nlsMessagesFile: Path get() = appDirectory.resolve("out").resolve("nls.messages.json")

    fun command(): List<String> = buildList {
        add(executable.toString())
        preloadScript?.let { add("--require"); add(it.toString()) }
        add(bootstrapScript.toString())
    }

    /** Variables to add to the inherited environment. */
    fun environment(): Map<String, String> = buildMap {
        // Only Electron needs telling not to be an editor; a plain node would just ignore it, but
        // setting it anyway would misdescribe what we launched.
        if (runtime == Runtime.ELECTRON) put("ELECTRON_RUN_AS_NODE", "1")
        put("VSCODE_ESM_ENTRYPOINT", ENTRY_POINT)
        put("VSCODE_EXTHOST_IPC_HOOK", pipeName)
        put("VSCODE_NLS_CONFIG", nlsConfigJson())
        put("VSCODE_PARENT_PID", parentPid.toString())
        put("VSCODE_HANDLES_UNCAUGHT_ERRORS", "true")
    }

    private fun nlsConfigJson(): String {
        val file = nlsMessagesFile.toString().replace("\\", "\\\\")
        return "{\"resolvedLanguage\":\"$locale\",\"defaultMessagesFile\":\"$file\"}"
    }

    companion object {
        const val ENTRY_POINT: String = "vs/workbench/api/node/extensionHostProcess"

        /**
         * Transport the host selects, by environment variable.
         *
         * Only [IPC_HOOK] is usable from a JVM: the other two require either a Node IPC channel
         * capable of passing a socket handle, or an Electron `MessagePort`.
         */
        const val IPC_HOOK: String = "VSCODE_EXTHOST_IPC_HOOK"
        const val WILL_SEND_SOCKET: String = "VSCODE_EXTHOST_WILL_SEND_SOCKET"
        const val WILL_SEND_MESSAGE_PORT: String = "VSCODE_WILL_SEND_MESSAGE_PORT"

        /** Windows named pipe path for [id]. */
        fun windowsPipeName(id: String): String = "\\\\.\\pipe\\$id"
    }
}
