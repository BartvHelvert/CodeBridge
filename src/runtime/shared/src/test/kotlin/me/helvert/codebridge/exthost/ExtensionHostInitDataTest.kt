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
    fun `a web extension is described as it declares itself, with no main for the Node host to load`() {
        val described = describe("""{"publisher":"acme","name":"web","version":"1.0.0","browser":"./dist/extension.js"}""")
        assertEquals(JsonPrimitive("./dist/extension.js"), described["browser"])
        assertEquals(null, described["main"])
    }
}
