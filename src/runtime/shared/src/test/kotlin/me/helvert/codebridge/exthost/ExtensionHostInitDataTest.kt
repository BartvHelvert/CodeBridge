package me.helvert.codebridge.exthost

import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.manifest.VsixManifestParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExtensionHostInitDataTest {
    private fun describe(manifest: String) = ExtensionHostInitData.describeExtension(
        assertNotNull(VsixManifestParser.parse(manifest).manifest), Path.of("extension").toAbsolutePath()
    )

    @Test
    fun `a web extension's browser entry is the main the host loads`() {
        val described = describe("""{"publisher":"acme","name":"web","version":"1.0.0","browser":"./dist/extension.js"}""")
        assertEquals(JsonPrimitive("./dist/extension.js"), described["main"])
        assertEquals(JsonPrimitive("./dist/extension.js"), described["browser"])
    }

    @Test
    fun `an extension's own main is kept`() {
        val described = describe("""{"publisher":"acme","name":"both","version":"1.0.0","main":"./out/node.js","browser":"./out/web.js"}""")
        assertEquals(JsonPrimitive("./out/node.js"), described["main"])
    }

    @Test
    fun `a declarative extension gets no main`() {
        assertEquals(null, describe("""{"publisher":"acme","name":"theme","version":"1.0.0"}""")["main"])
    }
}
