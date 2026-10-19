package me.helvert.codebridge.settings

import com.intellij.platform.eel.EelPlatform
import com.intellij.platform.eel.EelPlatform.Arch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins which platforms the bundled runtime covers.
 *
 * The mapping is not guesswork: it was checked against the asset list of the pinned VSCodium
 * release. The gap it records is the reason this is worth a test -- Windows on ARM has a
 * `reh-win32-x64` sibling but no `reh-win32-arm64`, so that plugin variant ships without a runtime,
 * and the settings page should say so rather than report it missing.
 */
class BundledRuntimeTest {

    @Test
    fun `covers the platforms vscodium publishes an extension host for`() {
        assertEquals("win32-x64", BundledRuntime.platform(EelPlatform.Windows(Arch.X86_64)))
        assertEquals("darwin-arm64", BundledRuntime.platform(EelPlatform.Darwin(Arch.ARM_64)))
        assertEquals("darwin-x64", BundledRuntime.platform(EelPlatform.Darwin(Arch.X86_64)))
        assertEquals("linux-arm64", BundledRuntime.platform(EelPlatform.Linux(Arch.ARM_64)))
        assertEquals("linux-x64", BundledRuntime.platform(EelPlatform.Linux(Arch.X86_64)))
    }

    @Test
    fun `reports no runtime for windows on arm`() {
        assertNull(BundledRuntime.platform(EelPlatform.Windows(Arch.ARM_64)))
    }

    @Test
    fun `reports no runtime for architectures with no build`() {
        assertNull(BundledRuntime.platform(EelPlatform.Windows(Arch.X86)))
        assertNull(BundledRuntime.platform(EelPlatform.Darwin(Arch.ARM_32)))
        assertNull(BundledRuntime.platform(EelPlatform.Linux(Arch.Unknown)))
    }
}
