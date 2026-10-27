package me.helvert.codebridge.json

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionContributorEP
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.JsonFileType
import com.intellij.json.JsonLanguage
import com.intellij.json.codeinsight.JsonStandardComplianceProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.helvert.codebridge.completion.SnippetStops
import me.helvert.codebridge.extensions.ExtensionInstallationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

/**
 * What typing in `launch.json` and `settings.json` offers once an extension declares its debugger
 * and settings: the IDE's own JSON completion and inspection, reading the schemas this module makes.
 */
class CodeBridgeJsonSchemaCompletionTest {
    private lateinit var fixture: CodeInsightTestFixture
    private val service get() = ExtensionInstallationService.getInstance()

    @BeforeEach
    fun setUp() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        fixture = factory.createCodeInsightFixture(
            factory.createLightFixtureBuilder(LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR, "codebridge-json-schema").fixture,
        )
        onEdt {
            fixture.setUp()
            // The light fixture doesn't load CodeBridge's descriptor; stand up what CodeBridge.json.backend.xml registers.
            JsonSchemaProviderFactory.EP_NAME.point.registerExtension(CodeBridgeJsonSchemaProviderFactory(), fixture.testRootDisposable)
            CompletionContributor.EP.point.registerExtension(
                CompletionContributorEP(JsonLanguage.INSTANCE.id, CodeBridgeLaunchCompletion::class.java.name, DefaultPluginDescriptor("codebridge-json-test")),
                LoadingOrder.FIRST, fixture.testRootDisposable,
            )
            CompletionContributor.EP.point.registerExtension(
                CompletionContributorEP(JsonLanguage.INSTANCE.id, CodeBridgeVariableCompletion::class.java.name, DefaultPluginDescriptor("codebridge-json-test")),
                LoadingOrder.FIRST, fixture.testRootDisposable,
            )
            // As CodeBridge.jsonfiles.xml maps it.
            ApplicationManager.getApplication().runWriteAction { FileTypeManager.getInstance().associateExtension(JsonFileType.INSTANCE, "code-workspace") }
            ApplicationManager.getApplication().messageBus.connect(fixture.testRootDisposable)
                .subscribe(ExtensionInstallationService.CHANGED, CodeBridgeJsonSchemaInstallationListener())
            fixture.enableInspections(JsonSchemaComplianceInspection::class.java)
        }
        service.install(probeVsix()) {}
    }

    @AfterEach
    fun tearDown() {
        try {
            service.uninstall("codebridge.json-schema-probe")
        } finally {
            onEdt {
                ApplicationManager.getApplication().runWriteAction { FileTypeManager.getInstance().removeAssociatedExtension(JsonFileType.INSTANCE, "code-workspace") }
                fixture.tearDown()
            }
        }
    }

    @Test
    fun `a configuration's type offers the installed debuggers`() {
        val offered = complete(".vscode/launch.json", """{"configurations": [{"type": "<caret>"}]}""")

        assertContains(offered, "probe")
        assertContains(offered, "other-probe")
    }

    @Test
    fun `a configuration offers its debugger's attributes and the common ones`() {
        val offered = complete(".vscode/launch.json", """{"configurations": [{"type": "probe", "request": "launch", "<caret>"}]}""")

        assertContains(offered, "program")
        assertContains(offered, "stopOnEntry")
        assertContains(offered, "preLaunchTask")
        assertTrue("pid" !in offered, "offered the attach shape's pid: $offered")
    }

    @Test
    fun `a new entry offers the debuggers' snippets`() {
        val offered = complete(".vscode/launch.json", """{"configurations": [{"name": "A", "type": "probe", "request": "attach"}, <caret>]}""")

        assertContains(offered, "Probe: Launch")
        assertContains(offered, "Probe: Attach")
    }

    @Test
    fun `a chosen snippet becomes an entry, with a comma when one follows`() {
        val (text, selected) = choose(
            ".vscode/launch.json",
            """{"configurations": [<caret>{"name": "A", "type": "probe", "request": "attach", "pid": 1}]}""",
            "Probe: Launch",
        )

        // `${1:<program>}`, the stop VS Code visits first, selected so typing replaces it.
        assertEquals("<program>", selected)

        val configurations = Json.parseToJsonElement(text).jsonObject["configurations"]!!.jsonArray
        assertEquals(2, configurations.size)
        val inserted = configurations.first().jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(
            mapOf("type" to "probe", "request" to "launch", "name" to "Launch", "program" to $$"${workspaceFolder}/<program>", "cwd" to $$"${workspaceFolder}"),
            inserted,
        )
        assertEquals(emptyList(), inspect())
    }

    @Test
    fun `Tab walks a chosen snippet's stops in VS Code's order, and leaves it after the last`() {
        choose(".vscode/launch.json", """{"configurations": [<caret>]}""", "Probe: Launch")
        onEdt {
            val editor = fixture.editor
            val selected = { editor.selectionModel.selectedText }
            val stops = assertNotNull(SnippetStops.of(editor))
            assertEquals("<program>", selected())

            fixture.type("app")
            stops.next()
            assertEquals("Launch", selected())
            stops.previous()
            assertEquals("app", selected())

            stops.next()
            stops.next()
            assertNull(SnippetStops.of(editor))
            assertEquals(null, selected())
            assertTrue(fixture.editor.document.text.contains($$"\"${workspaceFolder}/app\""))
        }
    }

    @Test
    fun `moving the caret out of a snippet stops walking it`() {
        choose(".vscode/launch.json", """{"configurations": [<caret>]}""", "Probe: Launch")
        onEdt {
            fixture.editor.caretModel.moveToOffset(0)
            assertNull(SnippetStops.of(fixture.editor))
        }
    }

    @Test
    fun `a workspace file's launch offers the snippets, and its variables and inputs`() {
        val snippets = complete("w.code-workspace", """{"folders": [], "launch": {"configurations": [<caret>]}}""")
        assertContains(snippets, "Probe: Launch")

        val launch = """{"folders": [], "launch": {"inputs": [{"id": "port", "type": "promptString"}], "configurations": [{"program": "%s"}]}}"""
        assertContains(complete("w.code-workspace", launch.format($$"${<caret>")), "workspaceFolder")
        assertEquals(listOf("port"), complete("w.code-workspace", launch.format($$"${input:<caret>")))
    }

    @Test
    fun `a workspace file's settings are not where variables are offered`() {
        val offered = complete("w.code-workspace", $$"""{"folders": [], "settings": {"probe.trace": "${<caret>"}}""")
        assertTrue("workspaceFolder" !in offered, offered.toString())
    }

    @Test
    fun `comments and trailing commas are allowed where VS Code allows them, and nowhere else`() {
        onEdt {
            JsonStandardComplianceProvider.EP_NAME.point.registerExtension(CodeBridgeJsoncCompliance(), fixture.testRootDisposable)
            for ((path, allowed) in listOf(".vscode/launch.json" to true, ".vscode/settings.json" to true, "w.code-workspace" to true, "data.json" to false)) {
                open(path, "{\n  // a comment\n  \"a\": [1,],\n}")
                val comment = PsiTreeUtil.findChildOfType(fixture.file, PsiComment::class.java)!!
                assertEquals(!allowed, JsonStandardComplianceProvider.shouldWarnAboutComment(comment), path)
                val array = PsiTreeUtil.findChildOfType(fixture.file, com.intellij.json.psi.JsonArray::class.java)!!
                assertEquals(!allowed, JsonStandardComplianceProvider.shouldWarnAboutTrailingComma(array), path)
            }
        }
    }

    @Test
    fun `a complete configuration is not reported`() {
        val problems = inspect(
            ".vscode/launch.json",
            """{
                "version": "0.2.0",
                "configurations": [
                    {"name": "Run", "type": "probe", "request": "launch", "program": "a", "stopOnEntry": true,
                     "preLaunchTask": "build", "presentation": {"group": "probes"}},
                    {"name": "Attach", "type": "other-probe", "request": "attach", "port": 9229}
                ],
                "compounds": [{"name": "Both", "configurations": ["Run", "Attach"], "stopAll": true}],
                "inputs": [{"id": "pick", "type": "pickString", "description": "Which?", "options": ["a", {"label": "B", "value": "b"}]}]
            }""",
        )

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `an attribute of the wrong type is reported`() {
        val problems = inspect(
            ".vscode/launch.json",
            """{"configurations": [{"name": "Run", "type": "probe", "request": "launch", "program": "a", "stopOnEntry": "yes"}]}""",
        )

        assertTrue(problems.isNotEmpty(), "expected a problem with stopOnEntry")
    }

    @Test
    fun `a launch entry missing what its shape requires is not told to be an attach`() {
        val problems = inspect(
            ".vscode/launch.json",
            """{"configurations": [{"name": "Launch", "type": "probe", "request": "launch", "cwd": "."}]}""",
        )

        assertTrue(problems.isNotEmpty(), "expected the missing program to be reported")
        assertTrue(problems.none { "attach" in it }, "reported as the attach shape: $problems")
    }

    @Test
    fun `a debugger's other request is checked against its own shape`() {
        assertEquals(emptyList(), inspect(".vscode/launch.json", """{"configurations": [{"name": "A", "type": "probe", "request": "attach", "pid": 4}]}"""))
        assertTrue(inspect(".vscode/launch.json", """{"configurations": [{"name": "A", "type": "probe", "request": "attach", "program": "a"}]}""").isNotEmpty())
    }

    @Test
    fun `an unknown debugger type is reported`() {
        assertTrue(inspect(".vscode/launch.json", """{"configurations": [{"name": "A", "type": "nope", "request": "launch"}]}""").isNotEmpty())
    }

    @Test
    fun `a setting of the wrong type is reported`() {
        assertTrue(inspect(".vscode/settings.json", """{"probe.enabled": "yes"}""").isNotEmpty())
        assertEquals(emptyList(), inspect(".vscode/settings.json", """{"probe.enabled": false, "editor.fontSize": 14}"""))
    }

    @Test
    fun `workspace settings offer the contributed settings`() {
        val offered = complete(".vscode/settings.json", """{"<caret>"}""")

        assertContains(offered, "probe.trace")
        assertContains(offered, "probe.enabled")
        // Declared by CodeBridge's workbench, which reads them, as VS Code's declares its own.
        assertContains(offered, "files.exclude")
        assertContains(offered, "files.autoSave")
    }

    @Test
    fun `a setting's value offers its enum`() {
        val offered = complete(".vscode/settings.json", """{"probe.trace": "<caret>"}""")

        assertContains(offered, "off")
        assertContains(offered, "verbose")
    }

    private fun complete(path: String, text: String): List<String> {
        var offered: List<String> = emptyList()
        onEdt {
            open(path, text)
            fixture.completeBasic()
            offered = fixture.lookupElements.orEmpty().flatMap { it.allLookupStrings }.map { it.trim('"') }
        }
        return offered
    }

    /** What choosing [label] from completion leaves: the file, and the text selected in it. */
    @Suppress("SameParameterValue")
    private fun choose(path: String, text: String, label: String): Pair<String, String?> {
        var result: Pair<String, String?> = "" to null
        onEdt {
            open(path, text)
            val item = fixture.completeBasic().orEmpty().first { label in it.allLookupStrings }
            fixture.lookup.currentItem = item
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            result = fixture.editor.document.text to fixture.editor.selectionModel.selectedText
        }
        return result
    }

    /**
     * What the schema inspection reports in the file, run by hand: the light fixture's highlighting
     * pass does not run it on these files, though it runs everywhere in an editor. In a read action
     * of its own, as the pass would run it: the inspection wants a job it can be canceled through.
     */
    private fun inspect(path: String, text: String): List<String> {
        onEdt { open(path, text) }
        return inspect()
    }

    /**
     * What the schema inspection reports in the file open now. Inspected in place rather than
     * written out again: on the Windows runners, rewriting a file the test had just saved failed
     * with "being used by another process", even with the temporary directory excluded from Defender.
     */
    private fun inspect(): List<String> {
        onEdt { PsiDocumentManager.getInstance(fixture.project).commitAllDocuments() }
        val file = fixture.file
        return runBlocking {
            readAction {
                val holder = ProblemsHolder(InspectionManager.getInstance(fixture.project), file, true)
                val session = LocalInspectionToolSession(file, file.textRange, file.textRange, null)
                val visitor = JsonSchemaComplianceInspection().buildVisitor(holder, true, session)
                PsiTreeUtil.processElements(file) { it.accept(visitor); true }
                holder.results.map { it.descriptionTemplate }
            }
        }
    }

    private fun open(path: String, text: String) {
        val caret = text.indexOf("<caret>")
        val file = fixture.addFileToProject(path, text.replace("<caret>", ""))
        fixture.configureFromExistingVirtualFile(file.virtualFile)
        if (caret >= 0) fixture.editor.caretModel.moveToOffset(caret)
    }

    /** A VSIX that is only a manifest: two debuggers and a few settings. */
    private fun probeVsix(): Path {
        val vsix = Files.createTempFile("json-schema-probe", ".vsix")
        ZipOutputStream(Files.newOutputStream(vsix)).use { zip ->
            zip.putNextEntry(ZipEntry("extension/package.json"))
            zip.write(PROBE_MANIFEST.toByteArray())
            zip.closeEntry()
        }
        return vsix
    }

    private fun onEdt(block: () -> Unit) = runInEdtAndWait(block)

    private companion object {
        const val PROBE_MANIFEST = $$"""{
            "publisher": "codebridge",
            "name": "json-schema-probe",
            "version": "1.0.0",
            "engines": {"vscode": "^1.80.0"},
            "contributes": {
                "debuggers": [
                    {
                        "type": "probe",
                        "label": "Probe",
                        "configurationSnippets": [
                            {
                                "label": "Probe: Launch",
                                "description": "Launch a program.",
                                "body": {
                                    "type": "probe", "request": "launch", "name": "${2:Launch}",
                                    "program": "^\"\\${workspaceFolder}/${1:<program>}\"",
                                    "cwd": "^\"\\${workspaceFolder}\""
                                }
                            },
                            {"label": "Probe: Attach", "body": {"type": "probe", "request": "attach", "name": "Attach", "pid": 0}}
                        ],
                        "configurationAttributes": {
                            "launch": {
                                "anyOf": [{"required": ["program"]}, {"required": ["cargo"]}],
                                "properties": {
                                    "program": {"type": "string", "description": "The program to debug."},
                                    "cargo": {"type": "object"},
                                    "cwd": {"type": ["string", "null"]},
                                    "stopOnEntry": {"type": "boolean", "default": false}
                                }
                            },
                            "attach": {
                                "properties": {"pid": {"type": ["integer", "string"]}}
                            }
                        }
                    },
                    {
                        "type": "other-probe",
                        "configurationAttributes": {"attach": {"properties": {"port": {"type": "number"}}}}
                    }
                ],
                "configuration": {
                    "title": "Probe",
                    "properties": {
                        "probe.trace": {"type": "string", "enum": ["off", "verbose"], "enumDescriptions": ["Nothing", "Everything"]},
                        "probe.enabled": {"type": "boolean", "default": true}
                    }
                }
            }
        }"""
    }
}
