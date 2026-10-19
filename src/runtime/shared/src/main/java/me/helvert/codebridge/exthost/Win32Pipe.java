package me.helvert.codebridge.exthost;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.*;

/**
 * The Windows byte-pipe transport. Loaded only on Windows, behind ExtensionHostPipeServer.
 * Java keeps invokeExact call sites statically typed, with no reflective argument arrays.
 */
final class Win32Pipe implements Closeable {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_PIPE_NOT_CONNECTED = 233;
    private static final int ERROR_PIPE_CONNECTED = 535;
    private static final int ERROR_OPERATION_ABORTED = 995;
    private static final int ERROR_IO_PENDING = 997;
    private static final int ERROR_NOT_FOUND = 1168;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long LAST_ERROR = CALL_STATE.byteOffset(groupElement("GetLastError"));

    // Win32 BOOL and DWORD are 32 bits, including on 64-bit Windows. HANDLE is a pointer.
    private static final MethodHandle CREATE_PIPE = bind("CreateNamedPipeW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CREATE_EVENT = bind("CreateEventW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CONNECT = bind("ConnectNamedPipe",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle READ = bind("ReadFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle WRITE = bind("WriteFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle RESULT = bind("GetOverlappedResult",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CANCEL = bind("CancelIoEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLOSE = bind("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle RESET = bind("ResetEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DISCONNECT = bind("DisconnectNamedPipe", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // OVERLAPPED: two ULONG_PTR fields, an Offset/OffsetHigh-or-pointer union, then HANDLE.
    // This implementation targets the 64-bit Windows runtimes supported by the IDE.
    private static final MemoryLayout OVERLAPPED = MemoryLayout.structLayout(
            JAVA_LONG.withName("Internal"), JAVA_LONG.withName("InternalHigh"),
            MemoryLayout.unionLayout(MemoryLayout.structLayout(JAVA_INT, JAVA_INT), ADDRESS),
            ADDRESS.withName("hEvent"));
    private static final long EVENT_OFFSET = OVERLAPPED.byteOffset(groupElement("hEvent"));

    private final Object state = new Object();
    private final Object closing = new Object();
    // Constructed on the launching thread, accessed by reader/writer, released by the closing thread.
    private final Arena arena = Arena.ofShared();
    private final MemorySegment control = arena.allocate(CALL_STATE);
    private final List<MemorySegment> events = new ArrayList<>();
    private final Operation connecting;
    private final Operation reading;
    private final Operation writing;
    private MemorySegment handle;
    private volatile boolean closed;
    private boolean connected;
    private boolean released;

    Win32Pipe(String endpoint) throws IOException {
        try {
            if (ADDRESS.byteSize() != 8) throw new IOException("Named pipes require a 64-bit Windows runtime");
            try (Arena names = Arena.ofConfined()) {
                MemorySegment name = names.allocateFrom(endpoint, StandardCharsets.UTF_16LE);
                // PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED; byte mode, blocking semantics.
                handle = (MemorySegment) CREATE_PIPE.invokeExact(control, name, 0x40000003, 0,
                        1, BUFFER_SIZE, BUFFER_SIZE, 0, MemorySegment.NULL);
                if (handle.address() == -1L) {
                    handle = MemorySegment.NULL;
                    throw error("CreateNamedPipeW", control);
                }
            }
            connecting = new Operation(false);
            reading = new Operation(true);
            writing = new Operation(true);
        } catch (Throwable failure) {
            try {
                release();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure(failure);
        }
    }

    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(KERNEL.find(name).orElseThrow(), descriptor,
                Linker.Option.captureCallState("GetLastError"));
    }

    private static IOException error(String operation, MemorySegment errors) {
        return error(operation, errors.get(JAVA_INT, LAST_ERROR));
    }

    private static IOException error(String operation, int code) {
        return new IOException(operation + " failed (Windows error " + code + ")");
    }

    private static IOException failure(Throwable failure) {
        if (failure instanceof IOException io) return io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        return new IOException("Windows named-pipe call failed", failure);
    }

    void accept() throws IOException {
        synchronized (connecting) {
            try {
                int result;
                synchronized (state) {
                    requireOpen();
                    if (connected) throw new IOException("Extension host pipe already accepted");
                    connecting.reset();
                    result = (int) CONNECT.invokeExact(connecting.errors, handle, connecting.overlapped);
                }
                if (result == 0) {
                    int error = connecting.error();
                    if (error == ERROR_IO_PENDING) {
                        int completion = connecting.complete();
                        if (completion != 0) throw error("ConnectNamedPipe", completion);
                    } else if (error != ERROR_PIPE_CONNECTED) throw error("ConnectNamedPipe", connecting.errors);
                }
                synchronized (state) {
                    requireOpen();
                    connected = true;
                }
            } catch (Throwable failure) {
                throw failure(failure);
            }
        }
    }

    int read(byte[] bytes) throws IOException {
        synchronized (reading) {
            try {
                int result;
                synchronized (state) {
                    if (closed) return -1;
                    requireConnected();
                    if (bytes.length == 0) return 0;
                    reading.reset();
                    result = (int) READ.invokeExact(reading.errors, handle, reading.buffer,
                            Math.min(bytes.length, BUFFER_SIZE), reading.count, reading.overlapped);
                }
                int error = result == 0 ? reading.error() : 0;
                if (error == ERROR_IO_PENDING) error = reading.complete();
                if (error == ERROR_BROKEN_PIPE || error == ERROR_PIPE_NOT_CONNECTED
                        || (error == ERROR_OPERATION_ABORTED && closed)) return -1;
                if (error != 0) throw error("ReadFile", error);
                int count = reading.count.get(JAVA_INT, 0);
                MemorySegment.copy(reading.buffer, 0, MemorySegment.ofArray(bytes), 0, count);
                return count;
            } catch (Throwable failure) {
                throw failure(failure);
            }
        }
    }

    void write(byte[] bytes) throws IOException {
        synchronized (writing) {
            try {
                synchronized (state) {
                    requireConnected();
                }
                int offset = 0;
                while (offset < bytes.length) {
                    int length = Math.min(bytes.length - offset, BUFFER_SIZE);
                    int result;
                    synchronized (state) {
                        requireConnected();
                        writing.reset();
                        MemorySegment.copy(MemorySegment.ofArray(bytes), offset, writing.buffer, 0, length);
                        result = (int) WRITE.invokeExact(writing.errors, handle, writing.buffer,
                                length, writing.count, writing.overlapped);
                    }
                    int error = result == 0 ? writing.error() : 0;
                    if (error == ERROR_IO_PENDING) error = writing.complete();
                    if (error != 0) throw error("WriteFile", error);
                    int count = writing.count.get(JAVA_INT, 0);
                    if (count <= 0 || count > length) throw new IOException("Invalid named-pipe write count: " + count);
                    offset += count;
                }
            } catch (Throwable failure) {
                throw failure(failure);
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (closing) {
            if (released) return;
            try {
                synchronized (state) {
                    closed = true;
                    // Serialize cancellation with submission so no I/O can start after this call.
                    int result = (int) CANCEL.invokeExact(control, handle, MemorySegment.NULL);
                    if (result == 0 && control.get(JAVA_INT, LAST_ERROR) != ERROR_NOT_FOUND) {
                        // Retain the arena if cancellation failed: Windows might still own pointers.
                        throw error("CancelIoEx", control);
                    }
                }
                // CancelIoEx only requests cancellation. Each operation must leave GetOverlappedResult
                // and finish accessing its buffer before the event, pipe and arena can be released.
                synchronized (connecting) {
                    synchronized (reading) {
                        synchronized (writing) {
                            try {
                                release();
                            } finally {
                                released = true;
                            }
                        }
                    }
                }
            } catch (Throwable failure) {
                throw failure(failure);
            }
        }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Extension host pipe closed");
    }

    private void requireConnected() throws IOException {
        requireOpen();
        if (!connected) throw new IOException("Extension host pipe not connected");
    }

    /**
     * Only called before publication or after every operation has completed.
     */
    private void release() throws Throwable {
        Throwable failure = null;
        try {
            if (!handle.equals(MemorySegment.NULL)) {
                int ignored = (int) DISCONNECT.invokeExact(control, handle);
                closeHandle(handle);
                handle = MemorySegment.NULL;
            }
        } catch (Throwable cleanup) {
            failure = cleanup;
        }
        for (MemorySegment event : events) {
            try {
                closeHandle(event);
            } catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        arena.close();
        if (failure != null) throw failure;
    }

    private void closeHandle(MemorySegment value) throws Throwable {
        int result = (int) CLOSE.invokeExact(control, value);
        if (result == 0) throw error("CloseHandle", control);
    }

    private final class Operation {
        final MemorySegment errors = arena.allocate(CALL_STATE);
        final MemorySegment overlapped = arena.allocate(OVERLAPPED);
        final MemorySegment count = arena.allocate(JAVA_INT);
        final MemorySegment buffer;
        final MemorySegment event;

        Operation(boolean hasBuffer) throws Throwable {
            buffer = hasBuffer ? arena.allocate(BUFFER_SIZE) : MemorySegment.NULL;
            event = (MemorySegment) CREATE_EVENT.invokeExact(errors, MemorySegment.NULL, 1, 0, MemorySegment.NULL);
            if (event.equals(MemorySegment.NULL)) throw Win32Pipe.error("CreateEventW", errors);
            events.add(event);
        }

        void reset() throws Throwable {
            overlapped.fill((byte) 0);
            overlapped.set(ADDRESS, EVENT_OFFSET, event);
            count.set(JAVA_INT, 0, 0);
            int result = (int) RESET.invokeExact(errors, event);
            if (result == 0) throw Win32Pipe.error("ResetEvent", errors);
        }

        int error() {
            return errors.get(JAVA_INT, LAST_ERROR);
        }

        int complete() throws Throwable {
            // No critical linker option: this call can block until the peer or close() completes I/O.
            int result = (int) RESULT.invokeExact(errors, handle, overlapped, count, 1);
            return result == 0 ? error() : 0;
        }
    }
}
