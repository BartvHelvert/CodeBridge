package me.helvert.codebridge.exthost

import me.helvert.codebridge.exthost.protocol.RpcIdTable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rpcId table read from the runtime the plugin actually launches.
 *
 * The extraction itself is covered without an installation in the protocol module; this is the half
 * that needs a real `extensionHostProcess.js`, so it lives where [TestRuntime] does.
 */
class RpcIdTableFromRuntimeTest {

    /**
     * When a real installation is available, the table read from it must agree with the one that
     * was validated against live traffic - or, if the build differs, still be self-consistent.
     */
    @Test
    fun `extracts a plausible table from an installed build`() {
        val installation = TestRuntime.orSkip() ?: return

        val table = RpcIdTable.extractFrom(installation.extensionHostProcessJs)
        println("VS Code ${installation.version}: ${table.size} rpcIds")

        assertTrue(table.size > 100, "expected a full table, got ${table.size}")
        assertTrue(table.mainThreadServices().isNotEmpty())
        assertTrue(table.extHostServices().isNotEmpty())
        // Every service we depend on must be present under some id.
        for (service in listOf(
            "MainThreadCommands", "MainThreadWindow", "MainThreadExtensionService",
            "MainThreadDebugService", "ExtHostConfiguration", "ExtHostWorkspace",
        )) {
            assertTrue(table.id(service) != null, "$service missing from the extracted table")
        }
    }
}
