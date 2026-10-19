package me.helvert.codebridge.debug

import java.io.Closeable

/** Complete DAP JSON messages, carried by extension-host RPC without stream framing. */
interface DapTransport : Closeable {
    fun connect(receive: (String) -> Unit, failed: (Throwable) -> Unit)
    fun send(json: String)
}
