package me.helvert.codebridge.launch

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The id the extension host knows a breakpoint by is saved with it, so it outlives restarts. */
class CodeBridgeBreakpointPropertiesTest {

    @Test
    fun `the host id survives being saved and loaded`() {
        val saved = XmlSerializer.serialize(CodeBridgeBreakpointProperties().apply { hostId = "6f1c" })
        val restored = CodeBridgeBreakpointProperties()
        restored.loadState(XmlSerializer.deserialize(saved, CodeBridgeBreakpointProperties::class.java))
        assertEquals("6f1c", restored.hostId)
    }
}
