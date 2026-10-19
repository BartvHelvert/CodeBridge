package me.helvert.codebridge.exthost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionHostLaunchSpecTest {

    /** An installation under a directory with a space in it, spelled the way this machine spells paths. */
    private val installation = Path.of(System.getProperty("java.io.tmpdir"), "VS Code").toAbsolutePath()

    private val spec = ExtensionHostLaunchSpec(
        executable = installation.resolve("Code.exe"),
        appDirectory = installation.resolve("resources").resolve("app"),
        pipeName = ExtensionHostLaunchSpec.windowsPipeName("codebridge-1"),
        parentPid = 4242,
    )

    @Test
    fun `launches the bootstrap script on the electron binary`() {
        val command = spec.command()
        assertEquals(installation.resolve("Code.exe").toString(), command[0])
        assertTrue(command[1].endsWith("bootstrap-fork.js"))
    }

    @Test
    fun `sets everything the host needs to start`() {
        val env = spec.environment()

        assertEquals("1", env["ELECTRON_RUN_AS_NODE"])
        assertEquals(ExtensionHostLaunchSpec.ENTRY_POINT, env["VSCODE_ESM_ENTRYPOINT"])
        assertEquals("\\\\.\\pipe\\codebridge-1", env[ExtensionHostLaunchSpec.IPC_HOOK])
        assertEquals("4242", env["VSCODE_PARENT_PID"])
        // The other two transports are never selected, so they must stay unset.
        assertNull(env[ExtensionHostLaunchSpec.WILL_SEND_SOCKET])
        assertNull(env[ExtensionHostLaunchSpec.WILL_SEND_MESSAGE_PORT])
    }

    @Test
    fun `nls config stays valid json whatever the separators`() {
        val nls = spec.environment()["VSCODE_NLS_CONFIG"]!!
        val config = Json.parseToJsonElement(nls).jsonObject

        // Windows' backslashes included: they are escaped, or the host could not read the file's name.
        assertEquals(spec.nlsMessagesFile.toString(), config["defaultMessagesFile"]!!.jsonPrimitive.content)
        assertTrue(spec.nlsMessagesFile.toString().endsWith("nls.messages.json"))
        assertEquals("en", config["resolvedLanguage"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a server package runs on plain node with no electron coaxing`() {
        // A remote-extension-host package has no asar, so there is nothing to talk Electron out of
        // and the variable would misdescribe what we launched.
        val server = spec.copy(
            executable = installation.resolve("node.exe"),
            appDirectory = installation,
            runtime = ExtensionHostLaunchSpec.Runtime.NODE,
        )
        val env = server.environment()

        assertNull(env["ELECTRON_RUN_AS_NODE"])
        // Everything else the host needs is unchanged; only the runtime differs.
        assertEquals(ExtensionHostLaunchSpec.ENTRY_POINT, env["VSCODE_ESM_ENTRYPOINT"])
        assertEquals("\\\\.\\pipe\\codebridge-1", env[ExtensionHostLaunchSpec.IPC_HOOK])
        assertEquals(installation.resolve("node.exe").toString(), server.command()[0])
    }
}
