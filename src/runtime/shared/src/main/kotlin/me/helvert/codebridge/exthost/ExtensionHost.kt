package me.helvert.codebridge.exthost

import kotlinx.coroutines.Deferred

/**
 * What the workbench calls into extensions through: one extension host's session, or every host the
 * project runs behind one front that sends each call where VS Code would.
 */
interface ExtensionHost {
    /** Completes once the host has ended, with why. */
    val termination: Deferred<Throwable>

    /**
     * Calls into the host, and returns the raw JSON it replied with; throws when it replied with an
     * error. The call is sent before this first suspends, so calls made one after another reach the
     * host in that order. Cancelling the caller cancels the call, which a [cancellable] one also tells
     * the host about.
     */
    suspend fun call(
        service: String,
        method: String,
        argsJson: String,
        cancellable: Boolean = false,
        buffers: Map<Int, ByteArray> = emptyMap(),
        /**
         * Arguments that are a `SerializableObjectWithBuffers`: their JSON slot holds the value, with
         * `{"$$ref$$": n}` where the n-th of these buffers goes.
         */
        objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
    ): String

    /**
     * Calls into the host for a method whose result is a `VSBuffer`, sent and cancelled as [call] is.
     * Null when the host answered `null` instead.
     */
    suspend fun callForBytes(service: String, method: String, argsJson: String, cancellable: Boolean = false): ByteArray?

    /**
     * Calls into the host for a method whose result is a `SerializableObjectWithBuffers`, sent and
     * cancelled as [call] is. The reply's [HostReply.json] is the value, its [HostReply.buffers] what
     * that value's references name.
     */
    suspend fun callWithBuffers(
        service: String,
        method: String,
        argsJson: String,
        cancellable: Boolean = false,
        buffers: Map<Int, ByteArray> = emptyMap(),
        objectBuffers: Map<Int, List<ByteArray>> = emptyMap(),
        /**
         * Whether to give up after a while; not for a method answered once the work it starts is
         * done, such as a kernel's `$executeCells`. A host that dies still fails it.
         */
        timesOut: Boolean = true,
    ): HostReply
}
