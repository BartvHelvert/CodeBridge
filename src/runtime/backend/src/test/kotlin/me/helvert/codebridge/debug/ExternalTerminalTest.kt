package me.helvert.codebridge.debug

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The script an external terminal window runs: what the program receives, past the quoting. Run
 * here without the window -- the part the window itself would run.
 */
class ExternalTerminalTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun `arguments reach a Windows program as they were given`() {
        assertEquals("\"a b\"", ExternalTerminal.windowsArgument("a b"))
        assertEquals("\"say \\\"hi\\\"\"", ExternalTerminal.windowsArgument("say \"hi\""))
        // A trailing backslash must not escape the closing quote.
        assertEquals("\"C:\\dir\\\\\"", ExternalTerminal.windowsArgument("C:\\dir\\"))
    }

    @Test
    fun `the script runs the program with its arguments intact`() {
        // A program that prints what it was given, read back as any program reads its arguments.
        val source = Files.createTempFile("PrintArgs", ".java")
        Files.writeString(
            source,
            "class PrintArgs { public static void main(String[] a) { for (String s : a) System.out.print(\"<\" + s + \">\"); } }"
        )
        val java = ProcessHandle.current().info().command().orElseThrow()
        val given = listOf("a b", "50%", "say \"hi\"", "it's", "C:\\dir\\")
        val args = listOf(java, source.toString()) + given
        try {
            val output =
                if (windows) run(ExternalTerminal.windowsScript(args, shellLine = false, title = "T"), ".cmd") {
                    listOf("cmd.exe", "/d", "/c", it, "run")
                } else run(
                    ExternalTerminal.unixScript(args, shellLine = false, cwd = null, changes = emptyMap()),
                    ".sh"
                ) {
                    listOf("/bin/sh", it)
                }
            val expected = given.joinToString("") { "<$it>" }
            assertTrue(output.contains(expected), "Expected '$expected' in '$output'")
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun `a Unix script sets what the adapter asked for, and goes to the working directory`() {
        val script = ExternalTerminal.unixScript(
            listOf("x"), shellLine = false, cwd = "/tmp/it's here",
            changes = mapOf("GREETING" to "hello 'world'", "GONE" to null, "not valid" to "skipped")
        )
        assertTrue(script.contains("cd '/tmp/it'\\''s here' || exit 1"), script)
        assertTrue(script.contains("export GREETING='hello '\\''world'\\'''"), script)
        assertTrue(script.contains("unset GONE"), script)
        assertTrue(!script.contains("not valid"), script)
    }

    private fun run(script: String, suffix: String, command: (String) -> List<String>): String {
        val file = Files.createTempFile("external-terminal-test", suffix)
        try {
            Files.writeString(file, script)
            val process = ProcessBuilder(command(file.toString())).redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(if (windows) java.io.File("NUL") else java.io.File("/dev/null")))
                .start()
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "The script ended")
            return process.inputStream.readAllBytes().decodeToString()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
