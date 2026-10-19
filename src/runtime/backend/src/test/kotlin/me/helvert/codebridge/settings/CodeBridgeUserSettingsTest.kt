package me.helvert.codebridge.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.helvert.codebridge.workspace.Jsonc
import me.helvert.codebridge.workspace.JsoncEdit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeBridgeUserSettingsTest {
    @TempDir lateinit var temp: Path

    private val target get() = temp.resolve("codebridge/settings.json")

    @Test
    fun `the first time, VS Code's own settings are copied as they are`() {
        val broken = Files.writeString(temp.resolve("broken.json"), "{ not json")
        val vsCode = Files.writeString(temp.resolve("code.json"), "{\n  // mine\n  \"files.autoSave\": \"afterDelay\",\n}\n")

        assertEquals(vsCode, CodeBridgeUserSettings.ensure(target, listOf(temp.resolve("missing.json"), broken, vsCode)),
            "One this could not read would be reported on every start")
        assertEquals(Files.readString(vsCode), Files.readString(target), "Comments and all")

        Files.writeString(vsCode, "{\"files.autoSave\":\"off\"}")
        assertNull(CodeBridgeUserSettings.ensure(target, listOf(vsCode)), "Only once; after that the two are independent")
        assertEquals("{\n  // mine\n  \"files.autoSave\": \"afterDelay\",\n}\n", Files.readString(target))
    }

    @Test
    fun `without VS Code it starts empty, and editable`() {
        assertNull(CodeBridgeUserSettings.ensure(target, listOf(temp.resolve("missing.json"))))
        val text = Files.readString(target)
        assertEquals(0, Json.parseToJsonElement(Jsonc.stripToJson(text)).jsonObject.size)
        val edited = JsoncEdit.set(text, listOf("files.autoSave"), JsonPrimitive("afterDelay"))
        assertEquals(JsonPrimitive("afterDelay"), Json.parseToJsonElement(Jsonc.stripToJson(edited)).jsonObject["files.autoSave"])
    }

    @Test
    fun `VS Code's settings are looked for where each platform keeps them`() {
        val home = Path.of("/home/me")
        fun first(os: String, env: Map<String, String> = emptyMap()) =
            CodeBridgeUserSettings.vsCodeUserSettings(os, env::get, home).first()

        assertEquals(Path.of("C:/Users/me/AppData/Roaming/Code/User/settings.json"),
            first("Windows 11", mapOf("APPDATA" to "C:/Users/me/AppData/Roaming")))
        assertEquals(home.resolve("Library/Application Support/Code/User/settings.json"), first("Mac OS X"))
        assertEquals(home.resolve(".config/Code/User/settings.json"), first("Linux"))
        assertEquals(Path.of("/xdg/Code/User/settings.json"), first("Linux", mapOf("XDG_CONFIG_HOME" to "/xdg")))
        assertEquals(listOf("Code", "Code - Insiders", "VSCodium"),
            CodeBridgeUserSettings.vsCodeUserSettings("Linux", { null }, home).map { it.parent.parent.fileName.toString() })
    }
}
