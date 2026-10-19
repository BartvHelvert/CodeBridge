package me.helvert.codebridge.language

import me.helvert.codebridge.exthost.VsCodeInstallation
import me.helvert.codebridge.settings.BundledRuntime
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltinLanguagesTest {

    @Test
    fun `launch json is jsonc because the runtime's configuration-editing says so`() {
        val extensions = Files.createTempDirectory("builtin-extensions")
        // As the runtime ships it, trimmed to what matters here.
        extensions.resolve("configuration-editing").createDirectories().resolve("package.json").writeText(
            """{"publisher": "vscode", "name": "configuration-editing", "version": "1.0.0", "contributes": {"languages": [
                {"id": "jsonc", "extensions": [".code-workspace"], "filenames": ["settings.json", "launch.json", "tasks.json"]},
                {"id": "json", "extensions": [".code-profile"]}
            ]}}"""
        )
        // A built-in without a manifest, and one contributing no languages, are passed over.
        extensions.resolve("node_modules").createDirectories()
        extensions.resolve("git").createDirectories().resolve("package.json")
            .writeText("""{"publisher": "vscode", "name": "git", "version": "1.0.0"}""")

        val languages = BuiltinLanguages.read(extensions)

        assertEquals(listOf("jsonc"), languages.filter { it.matches("launch.json") }.map { it.id })
        assertEquals(listOf("jsonc"), languages.filter { it.matches("project.code-workspace") }.map { it.id })
        assertTrue(languages.none { it.matches("package.json") })
    }

    @Test
    fun `a runtime without built-ins has none`() {
        assertEquals(emptyList(), BuiltinLanguages.read(Files.createTempDirectory("no-builtins").resolve("extensions")))
    }

    @Test
    fun `the workbench's built-ins name languages the host's do not, the closest claim first`() {
        val app = Files.createTempDirectory("builtin-installation")
        // The host's: `settings.json` by name.
        app.resolve("extensions/configuration-editing").createDirectories().resolve("package.json").writeText(
            """{"publisher": "vscode", "name": "configuration-editing", "version": "1.0.0", "contributes": {"languages": [
                {"id": "jsonc", "filenames": ["settings.json", "tsconfig.json"]}]}}"""
        )
        // The workbench's: `.java`, and `.json` by extension, which a name outranks.
        app.resolve("workbench-extensions/java").createDirectories().resolve("package.json").writeText(
            """{"publisher": "vscode", "name": "java", "version": "1.0.0", "contributes": {"languages": [
                {"id": "java", "extensions": [".java", ".jav"]}]}}"""
        )
        app.resolve("workbench-extensions/json").createDirectories().resolve("package.json").writeText(
            """{"publisher": "vscode", "name": "json", "version": "1.0.0", "contributes": {"languages": [
                {"id": "json", "extensions": [".json"]}, {"id": "code-workspace", "extensions": [".code-workspace.json"]}]}}"""
        )
        val installation = VsCodeInstallation(app.resolve("node"), app, VsCodeInstallation.Kind.SERVER)

        assertEquals(setOf("java"), BuiltinLanguages.forName(installation, "Shape.java"))
        assertEquals(listOf("jsonc", "json"), BuiltinLanguages.forName(installation, "tsconfig.json").toList())
        assertEquals("code-workspace", BuiltinLanguages.forName(installation, "x.code-workspace.json").first())
        assertEquals(emptySet(), BuiltinLanguages.forName(installation, "Main.kt"))
    }

    @Test
    fun `the bundled runtime names what vs code names`() {
        val directory = System.getProperty(BundledRuntime.DIRECTORY_PROPERTY)?.let(Path::of)
        val installation = directory?.let(VsCodeInstallation::from)
        assumeTrue(installation != null, "No unpacked runtime at $directory")
        fun opensAs(name: String) = BuiltinLanguages.forName(installation, name).firstOrNull()

        assertEquals("java", opensAs("Shape.java"))
        assertEquals("typescriptreact", opensAs("App.tsx"))
        assertEquals("shellscript", opensAs("build.sh"))
        assertEquals("json", opensAs("package.json"))
        assertEquals("jsonc", opensAs("tsconfig.json"))
        assertEquals("jsonc", opensAs("settings.json"))
        assertEquals("jsonc", opensAs("project.code-workspace"))
    }
}
