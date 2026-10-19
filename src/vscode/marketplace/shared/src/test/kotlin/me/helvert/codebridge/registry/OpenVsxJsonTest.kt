package me.helvert.codebridge.registry

import kotlin.test.*

/**
 * Reads real Open VSX responses, captured from the live API.
 *
 * Fixtures rather than hand-written JSON because the interesting cases are ones a fabricated sample
 * would not think to include: `downloads` carrying nine platform builds, `files` carrying a sha256
 * beside the download, icons living under `files` in search results as well as details.
 */
class OpenVsxJsonTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture $name" }
            .readBytes().decodeToString()

    @Test
    fun `reads a search page`() {
        val page = OpenVsxJson.parseSearch(fixture("openvsx-search.json"))

        assertEquals(0, page.offset)
        assertEquals(13, page.total)
        assertEquals(3, page.extensions.size)
        assertTrue(page.hasMore, "13 results with 3 returned should have more")

        val first = page.extensions.first()
        assertEquals("llvm-vs-code-extensions.lldb-dap", first.id)
        assertEquals("LLDB DAP", first.label)
        assertTrue(first.downloadCount > 0)
        assertNotNull(first.iconUrl, "search results carry an icon under files")
    }

    @Test
    fun `an extension knows its page on the registry`() {
        val first = OpenVsxJson.parseSearch(fixture("openvsx-search.json")).extensions.first()

        // Where the details pane's link goes. Deliberately the registry's page, not the publisher's
        // `homepage`, which for this extension -- as for most -- is a GitHub repository.
        assertEquals(
            "https://open-vsx.org/extension/llvm-vs-code-extensions/lldb-dap",
            first.pageUrl,
        )
    }

    @Test
    fun `reads a universal extension`() {
        val detail = OpenVsxJson.parseDetail(fixture("openvsx-detail-universal.json"))

        assertEquals("vadimcn.vscode-lldb", detail.extension.id)
        assertEquals("CodeLLDB", detail.extension.label)
        assertEquals("MIT", detail.license)
        assertEquals("^1.61.0", detail.engineRange)
        assertEquals(listOf("Debuggers"), detail.categories)
        assertEquals(listOf("workspace"), detail.extensionKind)
        assertTrue(detail.platforms().isEmpty(), "codelldb publishes only a universal package")
    }

    @Test
    fun `reads every platform build on offer`() {
        val detail = OpenVsxJson.parseDetail(fixture("openvsx-detail-platform.json"))

        assertEquals("rust-lang.rust-analyzer", detail.extension.id)
        assertTrue(detail.downloads.size > 5, "expected many platform builds, got ${detail.downloads.keys}")
        assertTrue("win32-x64" in detail.downloads)
        assertTrue("darwin-arm64" in detail.downloads)
        assertNotNull(detail.sha256, "a checksum is published beside the download")
    }

    @Test
    fun `prefers the build for this platform over the universal one`() {
        val detail = OpenVsxJson.parseDetail(fixture("openvsx-detail-platform.json"))

        val windows = assertNotNull(detail.downloadFor("win32-x64"))
        assertTrue(windows.contains("win32-x64"), windows)
        assertTrue(detail.isPlatformSpecific("win32-x64"))
    }

    @Test
    fun `falls back to the universal build when the platform has none`() {
        val detail = OpenVsxJson.parseDetail(fixture("openvsx-detail-platform.json"))

        // rust-analyzer publishes no riscv64 build, but its universal package is still installable.
        val fallback = assertNotNull(detail.downloadFor("linux-riscv64"))
        assertTrue(fallback.contains("rust-analyzer"), fallback)
        assertTrue(!detail.isPlatformSpecific("linux-riscv64"))
    }

    @Test
    fun `a universal-only extension resolves on every platform`() {
        val detail = OpenVsxJson.parseDetail(fixture("openvsx-detail-universal.json"))

        assertNotNull(detail.downloadFor("win32-x64"))
        assertNotNull(detail.downloadFor("darwin-arm64"))
    }

    @Test
    fun `derives the checksum url from any download`() {
        // `files.sha256` only describes the universal package, so a platform build needs its own.
        // Both are published next to the VSIX under the same name, verified against the live API.
        val detail = OpenVsxJson.parseDetail(fixture("openvsx-detail-platform.json"))
        val windows = assertNotNull(detail.downloadFor("win32-x64"))

        assertEquals(
            windows.removeSuffix(".vsix") + ".sha256",
            RegistryDetail.checksumUrl(windows),
        )
        assertNull(RegistryDetail.checksumUrl("https://example.test/not-a-package"))
    }

    @Test
    fun `skips entries it cannot read rather than losing the page`() {
        val mixed = """
            {"offset":0,"totalSize":2,"extensions":[
              {"namespace":"ok","name":"one","version":"1.0.0"},
              {"name":"missing-namespace","version":"1.0.0"}
            ]}
        """.trimIndent()

        val page = OpenVsxJson.parseSearch(mixed)
        assertEquals(1, page.extensions.size)
        assertEquals("ok.one", page.extensions.first().id)
    }

    @Test
    fun `rejects a response that is not an object`() {
        val failure = runCatching { OpenVsxJson.parseSearch("[]") }
        assertTrue(failure.isFailure)
        assertNull(failure.getOrNull())
    }
}
