package me.helvert.codebridge.exthost

import kotlin.test.*

class ExtensionActivationsTest {
    private val args = """[{"value":"Acme.Root","_lower":"acme.root"},{}]"""
    private fun failed(id: String = "acme.root", missing: String = "null") = MainThreadCall(
        "MainThreadExtensionService", $$"$onExtensionActivationError",
        """[{"value":"$id"},{"message":"Dependency failed","stack":"private stack"},$missing]""",
    )

    @Test
    fun `reported activation failure overrides a successful RPC reply ignoring identifier case`() {
        val state = ExtensionActivations()
        state.observe(failed())
        assertEquals(
            "Dependency failed",
            assertFailsWith<IllegalStateException> { state.checkResult(args, "true") }.message
        )
    }

    @Test
    fun `missing dependency is available for actionable error reporting`() {
        val failure =
            assertNotNull(ExtensionActivations.failure(failed(missing = """{"dependency":"acme.dependency"}""")))
        assertEquals("acme.dependency", failure.missingDependency)
        assertEquals("Dependency failed", failure.message)
    }

    @Test
    fun `an unrelated failure does not fail another extension and a new host starts clean`() {
        val state = ExtensionActivations()
        state.observe(failed("acme.other"))
        assertEquals("true", state.checkResult(args, "true"))
        state.observe(failed())
        assertEquals("true", ExtensionActivations().checkResult(args, "true"))
    }

    @Test
    fun `an unknown extension cannot appear to activate successfully`() {
        assertContains(assertFailsWith<IllegalStateException> {
            ExtensionActivations().checkResult(args, "false")
        }.message!!, "Acme.Root")
    }

    @Test
    fun `activation reports from other services are ignored`() {
        assertNull(ExtensionActivations.failure(failed().copy(service = "OtherService")))
    }
}
