package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `MainThreadSecretState` calls as `ExtHostSecretState` sends them. */
class SecretStateTest {

    @Test
    fun `reads each call's extension, key and value`() {
        assertEquals(SecretState.Request(SecretState.GET, "p.ext", "token", null),
            SecretState.request(SecretState.GET, """["p.ext","token"]"""))
        assertEquals(SecretState.Request(SecretState.SET, "p.ext", "token", "s3cret"),
            SecretState.request(SecretState.SET, """["p.ext","token","s3cret"]"""))
        assertEquals(SecretState.Request(SecretState.DELETE, "p.ext", "token", null),
            SecretState.request(SecretState.DELETE, """["p.ext","token"]"""))
        assertEquals(SecretState.Request(SecretState.KEYS, "p.ext", null, null),
            SecretState.request(SecretState.KEYS, """["p.ext"]"""))
    }

    @Test
    fun `a set without a value is refused rather than storing nothing`() {
        assertFailsWith<PayloadException> { SecretState.request(SecretState.SET, """["p.ext","token"]""") }
    }

    @Test
    fun `a change names the extension and the key`() {
        assertEquals("""[{"extensionId":"p.ext","key":"token"}]""", SecretState.changed("p.ext", "token"))
    }
}
