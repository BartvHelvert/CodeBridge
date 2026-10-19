package me.helvert.codebridge.workbench

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.helvert.codebridge.workspace.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The folders VS Code leaves out of search, found as the IDE is to exclude them. */
class ExcludedFoldersTest {
    private val root: Path = Files.createTempDirectory("excluded").toRealPath()

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun dirs(vararg paths: String) = paths.forEach { root.resolve(it).createDirectories() }
    private fun file(path: String, text: String) =
        root.resolve(path).apply { parent.createDirectories() }.writeText(text)

    private fun scan(
        settings: JsonObject = buildJsonObject {},
        folders: List<WorkspaceFolder> = listOf(WorkspaceFolder("root", root, 0))
    ) =
        ExcludedFolders(folders, { settings }, ignoreCase = false).scan()
            .map { root.relativize(it).toString().replace('\\', '/') }.toSet()

    @Test
    fun `a gitignored directory is excluded at any depth, and nothing under it is looked at`() {
        dirs("src", "target/debug/build", "greeter/src", "greeter/target")
        file(".gitignore", "target\n")
        file("target/debug/.gitignore", "!build\n")

        assertEquals(setOf("target", "greeter/target"), scan())
    }

    @Test
    fun `a rule with a slash is anchored to its file's directory`() {
        dirs("target", "greeter/target", "greeter/out", "out")
        file(".gitignore", "/target\n")
        file("greeter/.gitignore", "/out/\n")

        assertEquals(setOf("target", "greeter/out"), scan())
    }

    @Test
    fun `a negation re-includes, and a deeper file overrides a shallower one`() {
        dirs("build", "keep", "lib/build")
        file(".gitignore", "build\nkeep\n!keep\n")
        file("lib/.gitignore", "!build\n")

        assertEquals(setOf("build"), scan())
    }

    @Test
    fun `comments, blank lines and files are not folders`() {
        dirs("#notes", "logs")
        file(".gitignore", "# a comment\n\n\\#notes\n*.log\n.env\n")
        file("app.log", "")
        file(".env", "")

        assertEquals(setOf("#notes"), scan())
    }

    @Test
    fun `an ignore file and the repository's own exclude count too`() {
        dirs(".git/info", "vendor", "cache")
        file(".git/info/exclude", "cache\n")
        file(".ignore", "vendor\n")

        val settings = buildJsonObject { put("files.exclude", buildJsonObject { put("**/.git", false) }) }
        assertEquals(setOf("vendor", "cache"), scan(settings))
    }

    @Test
    fun `settings patterns set to true exclude, and ignore files stop counting when turned off`() {
        dirs("node_modules/pkg", "dist", "generated", "src/node_modules")
        file(".gitignore", "dist\n")
        val settings = buildJsonObject {
            put("files.exclude", buildJsonObject { put("generated", true); put("**/src", false) })
            put("search.exclude", buildJsonObject { put("**/node_modules", true) })
            put("search.useIgnoreFiles", false)
        }

        assertEquals(setOf("generated", "node_modules", "src/node_modules"), scan(settings))
    }

    @Test
    fun `a pattern with a sibling condition excludes no folder`() {
        dirs("out")
        val settings = buildJsonObject {
            put(
                "files.exclude",
                buildJsonObject { put("out", buildJsonObject { put("when", JsonPrimitive("$(basename).ts")) }) })
        }

        assertEquals(emptySet(), scan(settings))
    }

    @Test
    fun `braces in an ignore file are literal`() {
        dirs("{a,b}", "a")
        file(".gitignore", "{a,b}\n")

        assertEquals(setOf("{a,b}"), scan())
    }

    @Test
    fun `a directory not there yet is checked against the rules it would be under`() {
        dirs("greeter")
        file(".gitignore", "target\n")
        val folders =
            ExcludedFolders(listOf(WorkspaceFolder("root", root, 0)), { buildJsonObject {} }, ignoreCase = false)

        assertEquals(root.resolve("greeter/target"), folders.excludedAt(root.resolve("greeter/target/debug")))
        assertNull(folders.excludedAt(root.resolve("greeter/src")))
        assertNull(folders.excludedAt(root.resolveSibling("elsewhere/target")))
    }

    @Test
    fun `nested directories are reduced to the outermost`() {
        val paths = setOf(root.resolve("a"), root.resolve("a/b"), root.resolve("c"))

        assertEquals(setOf(root.resolve("a"), root.resolve("c")), CodeBridgeExcludedFolders.outermost(paths))
    }
}
