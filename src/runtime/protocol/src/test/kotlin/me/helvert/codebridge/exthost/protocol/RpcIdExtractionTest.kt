package me.helvert.codebridge.exthost.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcIdExtractionTest {

    /**
     * Shaped like a minified bundle: the factory has a meaningless name, is used for every service,
     * and other single-argument calls surround it.
     */
    private val bundle = """
        var q=e=>({_:e});other("MainThreadNotAService"),
        F={MainThreadAuthentication:q("MainThreadAuthentication"),MainThreadCommands:q("MainThreadCommands"),
        MainThreadNotebookDocuments:q("MainThreadNotebookDocumentsShape"),MainThreadWindow:q("MainThreadWindow")},
        G={ExtHostConfiguration:q("ExtHostConfiguration"),ExtHostWorkspace:q("ExtHostWorkspace")}
    """.trimIndent()

    @Test
    fun `finds the factory despite its minified name`() {
        val table = RpcIdTable.extract(bundle)

        assertEquals(6, table.size)
        assertEquals("MainThreadAuthentication", table.name(1))
        assertEquals("MainThreadCommands", table.name(2))
        assertEquals("MainThreadWindow", table.name(4))
        assertEquals(5, table.require("ExtHostConfiguration"))
    }

    /**
     * Entries whose property name differs from the string must still consume an id, or everything
     * after them shifts.
     */
    @Test
    fun `counts entries that do not name a service`() {
        val table = RpcIdTable.extract(bundle)

        assertEquals("MainThreadNotebookDocumentsShape", table.name(3))
        assertEquals("MainThreadWindow", table.name(4))
    }

    @Test
    fun `ignores unrelated single argument calls`() {
        val table = RpcIdTable.extract(bundle)
        assertNull(table.id("MainThreadNotAService"))
    }

    @Test
    fun `rejects a file that is not an extension host bundle`() {
        val failure = runCatching { RpcIdTable.extract("console.log('nothing to see')") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
