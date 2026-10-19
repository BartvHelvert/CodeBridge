package me.helvert.codebridge.workspace

import kotlinx.serialization.json.JsonPrimitive
import me.helvert.codebridge.launch.LaunchJsonParser
import me.helvert.codebridge.launch.substitute
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VariableSubstitutorTest {

    private val workspace: Path = Path.of("/home/dev/project")
    private val file: Path = Path.of("/home/dev/project/src/main/Main.kt")

    private fun context(
        environment: (String) -> String? = { null },
        configuration: (String) -> String? = { null },
        commands: (String) -> String? = { null },
        inputs: (String) -> String? = { null },
    ) = SimpleSubstitutionContext(
        workspaceFolder = workspace,
        namedWorkspaceFolders = mapOf("web" to Path.of("/home/dev/project/web")),
        currentFile = file,
        lineNumber = 42,
        selectedText = "selected",
        environment = environment,
        configuration = configuration,
        commands = commands,
        inputs = inputs,
    )

    private fun substitute(text: String, context: SubstitutionContext = context()) =
        VariableSubstitutor(context).substitute(text)

    @Test
    fun `expands workspace and file variables`() {
        assertEquals(workspace.toString(), substitute($$"${workspaceFolder}"))
        assertEquals("project", substitute($$"${workspaceFolderBasename}"))
        assertEquals(file.toString(), substitute($$"${file}"))
        assertEquals("Main.kt", substitute($$"${fileBasename}"))
        assertEquals("Main", substitute($$"${fileBasenameNoExtension}"))
        assertEquals(".kt", substitute($$"${fileExtname}"))
        assertEquals(Path.of("/home/dev/project/src/main").toString(), substitute($$"${fileDirname}"))
        assertEquals("main", substitute($$"${fileDirnameBasename}"))
        assertEquals(Path.of("src/main/Main.kt").toString(), substitute($$"${relativeFile}"))
        assertEquals(Path.of("src/main").toString(), substitute($$"${relativeFileDirname}"))
        assertEquals("42", substitute($$"${lineNumber}"))
        assertEquals("selected", substitute($$"${selectedText}"))
    }

    @Test
    fun `expands several variables inside one string`() {
        assertEquals(
            "$workspace/build/Main.kt",
            substitute($$"${workspaceFolder}/build/${fileBasename}"),
        )
    }

    @Test
    fun `expands prefixed variables`() {
        val context = context(
            environment = { if (it == "HOME") "/home/dev" else null },
            configuration = { if (it == "java.home") "/usr/lib/jvm" else null },
            commands = { if (it == "pickProcess") "1234" else null },
            inputs = { if (it == "port") "8080" else null },
        )
        assertEquals("/home/dev", substitute($$"${env:HOME}", context))
        assertEquals("/usr/lib/jvm", substitute($$"${config:java.home}", context))
        assertEquals("1234", substitute($$"${command:pickProcess}", context))
        assertEquals("8080", substitute($$"${input:port}", context))
        assertEquals(Path.of("/home/dev/project/web").toString(), substitute($$"${workspaceFolder:web}", context))
    }

    @Test
    fun `an undefined environment variable expands to empty, matching VS Code`() {
        val substitutor = VariableSubstitutor(context())
        assertEquals("", substitutor.substitute($$"${env:NOT_SET}"))
        assertTrue(substitutor.unresolved.isEmpty())
    }

    @Test
    fun `unresolved variables are reported and left verbatim`() {
        val substitutor = VariableSubstitutor(context())
        val result = substitutor.substitute($$"--pid=${command:extension.pickProcess}")

        assertEquals($$"--pid=${command:extension.pickProcess}", result)
        assertEquals(setOf("command:extension.pickProcess"), substitutor.unresolved)
    }

    @Test
    fun `unknown variables are reported and left verbatim`() {
        val substitutor = VariableSubstitutor(context())
        assertEquals($$"${nonsense}", substitutor.substitute($$"${nonsense}"))
        assertEquals(setOf("nonsense"), substitutor.unresolved)
    }

    @Test
    fun `expansion results are not rescanned`() {
        val context = context(environment = { $$"${workspaceFolder}" })
        val substitutor = VariableSubstitutor(context)
        assertEquals($$"${workspaceFolder}", substitutor.substitute($$"${env:TRICKY}"))
        assertTrue(substitutor.unresolved.isEmpty())
    }

    @Test
    fun `substitutes recursively through a configuration and leaves non-strings alone`() {
        val parsed = LaunchJsonParser.parse(
            $$"""
            { "configurations": [ {
                "type": "lldb", "request": "launch", "name": "App",
                "program": "${workspaceFolder}/target/app",
                "args": [ "--config", "${workspaceFolder}/app.toml" ],
                "env": { "LOG_FILE": "${workspaceFolder}/log.txt" },
                "stopOnEntry": true,
                "port": 5005
            } ] }
            """.trimIndent()
        )
        val configuration = assertNotNull(parsed.file.configuration("App"))

        val resolved = VariableSubstitutor(context()).substitute(configuration)

        assertEquals("$workspace/target/app", resolved.string("program"))
        assertEquals(listOf("--config", "$workspace/app.toml"), resolved.stringList("args"))
        assertEquals(mapOf("LOG_FILE" to "$workspace/log.txt"), resolved.env())
        assertEquals(true, resolved.boolean("stopOnEntry"))
        assertEquals(5005, resolved.int("port"))
        assertEquals(JsonPrimitive(5005), resolved.raw["port"])
    }

    @Test
    fun `finds variables without expanding them`() {
        val parsed = LaunchJsonParser.parse(
            $$"""
            { "configurations": [ {
                "type": "java", "request": "launch", "name": "App",
                "mainClass": "${input:mainClass}",
                "args": [ "${command:pickProcess}" ]
            } ] }
            """.trimIndent()
        )
        val configuration = assertNotNull(parsed.file.configuration("App"))

        assertEquals(
            listOf("input:mainClass", "command:pickProcess"),
            VariableSubstitutor.findVariables(configuration.raw),
        )
    }
}
