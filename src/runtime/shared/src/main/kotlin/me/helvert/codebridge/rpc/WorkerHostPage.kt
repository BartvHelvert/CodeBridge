package me.helvert.codebridge.rpc

/**
 * Where a web worker extension host's page is served from, in the client's browser: an address of
 * its own per host, which the client answers itself and the backend's files are fetched under. See
 * `HostedWorkerHosts` for the backend's half, `WorkerHostBrowser` for the client's.
 */
object WorkerHostPage {
    fun host(id: String): String = "$id.vscode-exthost.vscode-cdn.net"

    /** The page itself, which the client makes. */
    const val INDEX: String = "/index.html"

    /** VS Code's worker host script. */
    const val SCRIPT: String = "/worker/extensionHostWorkerMain.js"

    /** The strings the script looks up by number. */
    const val MESSAGES: String = "/worker/nls.messages.json"

    /** An extension's files, under its id: `/extensions/<id>/<path>`. */
    const val EXTENSIONS: String = "/extensions/"
}
