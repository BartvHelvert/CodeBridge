package me.helvert.codebridge.uitest.vscode

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.codeEditor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.ide.starter.ide.IDERemDevTestContext
import com.intellij.ide.starter.junit5.RemoteDevRun
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.performanceTesting.commands.CommandChain
import com.intellij.tools.ide.performanceTesting.commands.openFile
import com.intellij.tools.ide.performanceTesting.commands.waitForSmartMode
import me.helvert.codebridge.uitest.Installed
import me.helvert.codebridge.uitest.builtAgainst
import me.helvert.codebridge.uitest.install
import me.helvert.codebridge.uitest.runSplitIde
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Split-mode regressions with local fixtures, independent of developer projects and extensions. */
@ExtendWith(RemoteDevRun::class)
class SplitModeTabColorProbe {
    @Test
    fun `non-project file tab colour reaches the client`() {
        val project = javaProject()
        val outside = project.parent.resolve("outside.txt").also { it.writeText("Outside the project\n") }
        val context = context("split-mode-tab-colour", project)
        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode().openFile(outside.toString()),
            jdk = true,
        )
        var failure: Throwable? = null
        try {
            val driver = run.driver
            val backend = awaitValue("backend project", {
                runCatching { driver.singleProject(RdTarget.BACKEND) }.getOrNull()?.takeIf { it.isInitialized() }
            }) { it != null }!!
            val expected = awaitValue("backend non-project tab colour", {
                tabColour(driver, backend, RdTarget.BACKEND, outside)
            }) { it != null }
            assertNotNull(expected)
            val frontend = awaitValue("frontend project", {
                runCatching { driver.singleProject(RdTarget.FRONTEND) }.getOrNull()?.takeIf { it.isInitialized() }
            }) { it != null }!!
            val actual = awaitValue("matching non-project tab colour in the client", {
                tabColour(driver, frontend, RdTarget.FRONTEND, outside)
            }) { it == expected }
            assertEquals(expected, actual)
            assertEquals("Outside the project\n", driver.ideFrame { }.codeEditor().text)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                run.closeIdeAndWait()
            } catch (closeError: Throwable) {
                if (failure == null) throw closeError
                failure.addSuppressed(closeError)
            }
        }
    }

    @Test
    fun `java type hierarchy opens in the client with CodeBridge installed`() {
        val context = context("split-mode-java-hierarchy", javaProject())
        val run = context.runSplitIde(
            commands = CommandChain().waitForSmartMode(), open = "src/Shape.java",
            jdk = true,
        )
        try {
            val driver = run.driver
            val editor = driver.ideFrame { }.codeEditor()
            editor.goToPosition(1, 24) // The declaration of Shape, whose subtype is Circle.
            driver.invokeAction("TypeHierarchy", component = editor.component)
            val texts = awaitValue("Circle in the client's type hierarchy", {
                driver.ideFrame { }.getAllTexts().map { it.text }
            }) { values -> values.any { it.contains("Circle") } }
            assertTrue(texts.any { it.contains("Shape") })
        } finally {
            run.closeIdeAndWait()
        }
    }

    private fun context(name: String, project: Path): IDERemDevTestContext {
        val context = Starter.newContext(
            testName = name,
            testCase = builtAgainst(project),
        ) as IDERemDevTestContext
        context.install(Installed.CODEBRIDGE)
        val clientSystem = Files.createTempDirectory(
            Files.createDirectories(Path.of(System.getProperty("codebridge.test.work"))), "c",
        )
        context.frontendIDEContext.applyVMOptionsPatch { addSystemProperty("idea.system.path", clientSystem) }
        return context
    }

    private fun javaProject(): Path {
        val project = Files.createDirectories(Files.createTempDirectory("codebridge-split-probe").resolve("project"))
        Files.createDirectories(project.resolve("src")).resolve("Shape.java").writeText(
            "public abstract class Shape {}\nfinal class Circle extends Shape {}\n",
        )
        project.resolve("fixture.iml").writeText(
            $$"""<module type="JAVA_MODULE" version="4">
              <component name="NewModuleRootManager">
                <content url="file://$MODULE_DIR$"><sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" /></content>
                <orderEntry type="sourceFolder" forTests="false" />
              </component>
            </module>""".trimIndent(),
        )
        Files.createDirectories(project.resolve(".idea")).resolve("modules.xml").writeText(
            $$"""<project version="4"><component name="ProjectModuleManager"><modules>
              <module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml" />
            </modules></component></project>""".trimIndent(),
        )
        return project
    }

    private fun tabColour(driver: Driver, project: Project, target: RdTarget, path: Path): Int? =
        driver.withContext(OnDispatcher.EDT) {
            val file = if (target == RdTarget.BACKEND) {
                driver.utility(LocalFileSystemRef::class, target).getInstance()
                    .findFileByPath(path.invariantSeparatorsPathString)
            } else driver.service(FileEditorManagerRef::class, project, target).getOpenFiles()
                .singleOrNull { it.getName() == path.fileName.toString() }
            if (file == null) return@withContext null
            driver.utility(TabPresentationRef::class, target).getEditorTabBackgroundColor(project, file)?.getRGB()
        }

    private fun <T> awaitValue(description: String, read: () -> T, matches: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var last: T
        do {
            last = read()
            if (matches(last)) return last
            Thread.sleep(300)
        } while (System.nanoTime() < deadline)
        error("Timed out waiting for $description; last observed: $last")
    }

    @Remote("com.intellij.openapi.fileEditor.FileEditorManager")
    interface FileEditorManagerRef {
        fun getOpenFiles(): Array<VirtualFile>
    }

    @Remote("com.intellij.openapi.vfs.LocalFileSystem")
    interface LocalFileSystemRef {
        fun getInstance(): LocalFileSystemRef
        fun findFileByPath(path: String): VirtualFile?
    }

    @Remote("com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil")
    interface TabPresentationRef {
        fun getEditorTabBackgroundColor(project: Project, file: VirtualFile): ColorRef?
    }

    @Remote("java.awt.Color")
    interface ColorRef {
        fun getRGB(): Int
    }
}
