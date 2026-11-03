package me.helvert.codebridge.uitest.runtime

import com.intellij.ide.starter.junit5.RemoteDevRun
import me.helvert.codebridge.uitest.awaitProbeValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@ExtendWith(RemoteDevRun::class)
class SplitModeWebExtensionTest {
    /**
     * An extension with a `browser` entry and no `main`, as Excalidraw's is: VS Code runs it in its web
     * worker extension host, and CodeBridge, which has none, in the Node host.
     */
    @Test
    fun `a web extension runs in the extension host`() =
        withWorkbenchProbe("split-mode-web-extension", "let value = 1;\n", extensions = listOf(webExtension())) {
            awaitProbeValue<String?>("the web extension's command", { command("webProbe.ran") }) { it == "true" }
        }

    private fun webExtension(): Path {
        val vsix = Files.createTempFile("codebridge-web-probe", ".vsix")
        ZipOutputStream(Files.newOutputStream(vsix)).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry(
                "extension/package.json",
                """{"publisher":"codebridge","name":"web-probe","version":"1.0.0","engines":{"vscode":"^1.63.0"},""" +
                    """"activationEvents":["onStartupFinished"],"browser":"./dist/extension.js"}""",
            )
            entry(
                "extension/dist/extension.js",
                """const vscode = require("vscode");
                  |exports.activate = context => context.subscriptions.push(vscode.commands.registerCommand("webProbe.ran", () => true));
                  |""".trimMargin(),
            )
        }
        return vsix
    }
}
