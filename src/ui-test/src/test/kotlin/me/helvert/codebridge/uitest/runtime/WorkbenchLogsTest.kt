package me.helvert.codebridge.uitest.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertFalse

class WorkbenchLogsTest {
    @TempDir
    lateinit var home: Path

    @Test
    fun `a scenario sees new messages from every backend log, including logs created later`() {
        fun log(directory: String, text: String): Path =
            Files.createDirectories(home.resolve(directory)).resolve("idea.log").also { it.writeText(text) }

        val first = log("first/log", "old first\n")
        val second = log("second/log", "old second\n")
        val frontend = log("second/frontend/log", "client message\n")
        val scenario = WorkbenchLogs(home).snapshot()

        first.appendText("new first error\n")
        second.appendText("new second error\n")
        frontend.appendText("client error\n")
        log("third/log", "new backend error\n")

        val observed = scenario.read()
        assertContains(observed, "new first error")
        assertContains(observed, "new second error")
        assertContains(observed, "new backend error")
        assertFalse(observed.contains("old"))
        assertFalse(observed.contains("client"))
    }
}
