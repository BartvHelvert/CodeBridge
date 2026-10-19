package me.helvert.codebridge.task

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.exthost.protocol.payload.Tasks
import me.helvert.codebridge.ide.EditorFixture
import me.helvert.codebridge.started
import me.helvert.codebridge.workbench.HostedTerminals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The ids a task is known by. The host files a `CustomExecution` under the id `$createTaskId` gives
 * it, then finds it again by the id the task is started under -- `$getTaskExecution`'s.
 */
class HostedTasksTest : EditorFixture() {

    private fun task(name: String) =
        """{"name":"$name","definition":{"type":"cargo"},"execution":{"customExecution":"customExecution"},"source":{"label":"CodeLLDB"}}"""

    @Test
    fun `a task keeps one id from creating it to running it`() {
        val tasks = HostedTasks(
            fixture.project,
            HostedTerminals(fixture.project, Files.createTempDirectory("codebridge-tasks")) { _, _, _ -> "" }) { _, _, _ -> "" }

        fun call(method: String, task: String) =
            started { tasks.handle(MainThreadCall(Tasks.SERVICE, method, "[$task]")) }.get(10, TimeUnit.SECONDS).json!!

        val created = Json.parseToJsonElement(call(Tasks.CREATE_ID, task("cargo run"))).jsonPrimitive.content
        assertEquals(created, Json.parseToJsonElement(call(Tasks.CREATE_ID, task("cargo run"))).jsonPrimitive.content)
        val execution = Json.parseToJsonElement(call(Tasks.GET_EXECUTION, task("cargo run"))).jsonObject
        assertEquals(
            created,
            execution["id"]!!.jsonPrimitive.content,
            "Started under the id its custom execution was filed by"
        )

        val other = Json.parseToJsonElement(call(Tasks.CREATE_ID, task("cargo test"))).jsonPrimitive.content
        assertNotEquals(created, other)
    }
}
