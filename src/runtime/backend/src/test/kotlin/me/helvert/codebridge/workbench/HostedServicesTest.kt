package me.helvert.codebridge.workbench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.helvert.codebridge.exthost.*
import me.helvert.codebridge.exthost.protocol.payload.Configuration
import me.helvert.codebridge.exthost.protocol.payload.LogLevel
import me.helvert.codebridge.exthost.protocol.payload.UriComponents
import me.helvert.codebridge.started
import me.helvert.codebridge.workspace.JsoncEdit
import me.helvert.codebridge.workspace.CodeBridgeWorkspace
import me.helvert.codebridge.workspace.WorkspaceChoices
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.concurrent.*
import kotlin.test.*

class HostedServicesTest {
    @TempDir lateinit var temp: Path
    private val notifications = CopyOnWriteArrayList<Triple<String, String, String>>()
    private val notify: (String, String, String) -> Unit = { service, method, args -> notifications.add(Triple(service, method, args)) }
    private fun call(service: String, method: String, vararg values: JsonElement) = MainThreadCall(service, method, JsonArray(values.toList()).toString())
    private fun uri(path: Path) = UriComponents.of(path).toJson()
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test fun `mementos survive restart with global and workspace isolation`() {
        val root = temp.resolve("state")
        val project = temp.resolve("project")
        val value = json("{\"count\":42,\"nested\":{\"unicode\":\"café\"}}")
        fun initialize(global: Boolean, id: String = "publisher.ext") = call("MainThreadStorage",
            $$"$initializeExtensionStorage", JsonPrimitive(global), JsonPrimitive(id))
        HostedStorage(root, project, notify).use { storage ->
            assertNull(storage.handle(initialize(true)))
            for (global in listOf(true, false)) storage.handle(call("MainThreadStorage",
                $$"$setValue", JsonPrimitive(global), JsonPrimitive("publisher.ext"), value))
        }
        HostedStorage(root, project, notify).use { restarted ->
            for (global in listOf(true, false)) assertEquals(value, json(json(restarted.handle(initialize(global))!!).jsonPrimitive.content))
            assertNull(restarted.handle(initialize(true, "other.ext")))
        }
        HostedStorage(root, temp.resolve("other-project"), notify).use { other ->
            assertNotNull(other.handle(initialize(true)))
            assertNull(other.handle(initialize(false)))
        }
    }

    @Test fun `global changes reach other active projects and disposal stops notifications`() {
        val root = temp.resolve("state")
        val listener = HostedStorage(root, temp.resolve("a"), notify)
        listener.handle(call("MainThreadStorage", $$"$initializeExtensionStorage", JsonPrimitive(true), JsonPrimitive("p.ext")))
        HostedStorage(root, temp.resolve("b")) { _, _, _ -> }.use { writer ->
            val update = call("MainThreadStorage", $$"$setValue", JsonPrimitive(true), JsonPrimitive("p.ext"), json("{\"a\":1}"))
            writer.handle(update)
            assertEquals($$"$acceptValue", notifications.single().second)
            assertEquals("{\"a\":1}", json(notifications.single().third).jsonArray[2].jsonPrimitive.content)
            listener.close(); notifications.clear(); writer.handle(update)
            assertTrue(notifications.isEmpty())
        }
    }

    private class MemoryStore : HostedSecrets.Store {
        val entries = ConcurrentHashMap<Pair<String, String>, String>()
        override fun read(service: String, account: String) = entries[service to account]
        override fun write(service: String, account: String, value: String?) {
            if (value == null) entries.remove(service to account) else entries[service to account] = value
        }
    }

    private fun secret(secrets: HostedSecrets, method: String, vararg values: String) =
        started { secrets.handle(call("MainThreadSecretState", method, *values.map(::JsonPrimitive).toTypedArray())) }.get(5, TimeUnit.SECONDS)

    @Test fun `secrets are kept per extension, listed by key, and deleted`() {
        val store = MemoryStore()
        HostedSecrets(store, notify).use { secrets ->
            assertNull(secret(secrets, $$"$getPassword", "p.ext", "token").json)
            assertEquals("[]", secret(secrets, $$"$getKeys", "p.ext").json)
            val long = "x".repeat(10_000)
            secret(secrets, $$"$setPassword", "p.ext", "token", long)
            secret(secrets, $$"$setPassword", "p.ext", "refresh", "r")
            secret(secrets, $$"$setPassword", "p.ext", "token", long)
            assertEquals(long, json(secret(secrets, $$"$getPassword", "p.ext", "token").json!!).jsonPrimitive.content)
            assertEquals(listOf("token", "refresh"), json(secret(secrets, $$"$getKeys", "p.ext").json!!).jsonArray.map { it.jsonPrimitive.content })
            assertNull(secret(secrets, $$"$getPassword", "other.ext", "token").json)
            assertEquals("[]", secret(secrets, $$"$getKeys", "other.ext").json)
            secret(secrets, $$"$deletePassword", "p.ext", "token")
            secret(secrets, $$"$deletePassword", "p.ext", "refresh")
            assertNull(secret(secrets, $$"$getPassword", "p.ext", "token").json)
            assertEquals("[]", secret(secrets, $$"$getKeys", "p.ext").json)
            assertTrue(store.entries.isEmpty(), "Nothing is left behind: ${store.entries.keys}")
        }
    }

    @Test fun `a changed secret is announced to every project's host, until it closes`() {
        val store = MemoryStore()
        val other = CopyOnWriteArrayList<Triple<String, String, String>>()
        val listener = HostedSecrets(store) { service, method, args -> other.add(Triple(service, method, args)) }
        HostedSecrets(store, notify).use { writer ->
            secret(writer, $$"$setPassword", "p.ext", "token", "t")
            for (heard in listOf(notifications, other)) {
                val (service, method, args) = heard.single()
                assertEquals("ExtHostSecretState" to $$"$onDidChangePassword", service to method)
                assertEquals(json("""[{"extensionId":"p.ext","key":"token"}]"""), json(args))
            }
            assertEquals("t", json(secret(listener, $$"$getPassword", "p.ext", "token").json!!).jsonPrimitive.content)
            listener.close(); notifications.clear(); other.clear()
            secret(writer, $$"$deletePassword", "p.ext", "token")
            assertEquals(1, notifications.size)
            assertTrue(other.isEmpty())
        }
    }

    @Test fun `file API preserves binary and handles spaces and URI punctuation`() {
        val fs = HostedFileSystem()
        val file = temp.resolve("nested space/100% # café.bin")
        val bytes = byteArrayOf(0, -1, -128, 65, 10)
        fs.handle(call("MainThreadFileSystem", $$"$writeFile", uri(file), JsonNull).copy(buffers = mapOf(1 to bytes)))
        assertContentEquals(bytes, fs.handle(call("MainThreadFileSystem", $$"$readFile", uri(file))).bytes)
        val stat = json(fs.handle(call("MainThreadFileSystem", $$"$stat", uri(file))).json!!).jsonObject
        assertEquals(5, stat["size"]!!.jsonPrimitive.int)
        assertEquals(1, stat["type"]!!.jsonPrimitive.int)
        val listing = json(fs.handle(call("MainThreadFileSystem", $$"$readdir", uri(file.parent))).json!!).jsonArray
        assertEquals(file.fileName.toString(), listing.single().jsonArray[0].jsonPrimitive.content)
    }

    @Test fun `rename refuses overwrite and reports missing files in VS Code error vocabulary`() {
        val fs = HostedFileSystem()
        val source = Files.writeString(temp.resolve("source"), "source")
        val target = Files.writeString(temp.resolve("target"), "target")
        val rename = call("MainThreadFileSystem", $$"$rename", uri(source), uri(target), json("{\"overwrite\":false}"))
        assertEquals("EntryExists", assertFailsWith<MainThreadException> { fs.handle(rename) }.remoteName)
        assertEquals("target", Files.readString(target))
        fs.handle(call("MainThreadFileSystem", $$"$rename", uri(source), uri(target), json("{\"overwrite\":true}")))
        assertEquals("source", Files.readString(target))
        assertEquals("EntryNotFound", assertFailsWith<MainThreadException> {
            fs.handle(call("MainThreadFileSystem", $$"$readFile", uri(source)))
        }.remoteName)
    }

    @Test fun `workspace findFiles honors relative roots globs exclusions and limits`() {
        val root = Files.createDirectories(temp.resolve("project"))
        for (name in listOf("root.kt", "src/one.kt", "src/two.java", "generated/skip.kt", "src/skip.txt")) {
            val file = root.resolve(name); Files.createDirectories(file.parent); Files.writeString(file, "x")
        }
        val workspace = HostedWorkspace(CodeBridgeWorkspace.single(root)) { json("{\"files.exclude\":{\"generated\":true}}") as JsonObject }
        fun search(folder: JsonElement, options: String) = json(workspace.handle(call("MainThreadWorkspace",
            $$"$startFileSearch", folder, json(options)))!!).jsonArray
            .map { UriComponents.from(it)!!.toPath() }.toSet()
        assertEquals(setOf(root.resolve("root.kt"), root.resolve("src/one.kt"), root.resolve("src/two.java")),
            search(JsonNull, "{\"includePattern\":\"**/*.{kt,java}\"}"))
        assertEquals(setOf(root.resolve("src/one.kt")), search(uri(root.resolve("src")), "{\"includePattern\":\"*.kt\"}"))
        assertEquals(1, search(JsonNull, "{\"includePattern\":\"**/*.kt\",\"maxResults\":1}").size)
        assertEquals(3, search(JsonNull, "{\"includePattern\":\"**/*.kt\",\"disregardExcludeSettings\":true}").size)
        assertFailsWith<CancellationException> { workspace.handle(call("MainThreadWorkspace",
            $$"$startFileSearch", JsonNull, json("{}"))) { true } }
    }

    @Test fun `copy overwrite replaces a directory and refuses overlapping trees`() {
        val fs = HostedFileSystem()
        val source = Files.createDirectories(temp.resolve("source"))
        val target = Files.createDirectories(temp.resolve("target"))
        Files.writeString(source.resolve("new.txt"), "new")
        Files.writeString(target.resolve("obsolete.txt"), "old")
        fs.handle(call("MainThreadFileSystem", $$"$copy", uri(source), uri(target), json("{\"overwrite\":true}")))
        assertEquals("new", Files.readString(target.resolve("new.txt")))
        assertFalse(Files.exists(target.resolve("obsolete.txt")))
        assertFailsWith<MainThreadException> {
            fs.handle(call("MainThreadFileSystem", $$"$copy", uri(source), uri(source.resolve("child")), json("{\"overwrite\":true}")))
        }
        fs.handle(call("MainThreadFileSystem", $$"$delete", uri(target), json("{\"recursive\":true}")))
        assertFalse(Files.exists(target)); assertTrue(Files.exists(source.resolve("new.txt")))
    }

    @Test fun `saved JSONC settings reload while invalid settings retain last good state`() {
        val folder = Files.createDirectories(temp.resolve(".vscode"))
        val file = Files.writeString(folder.resolve("settings.json"), "{ // comment\n \"server.enabled\": true, }")
        val failures = mutableListOf<Throwable>()
        val configuration = HostedConfiguration(temp, json("{\"server.mode\":\"default\"}") as JsonObject, notify, failures::add)
        Files.writeString(file, "{\"server.enabled\":false,\"[kotlin]\":{\"editor.tabSize\":2}}")
        configuration.reload()
        val payload = json(notifications.single().third).jsonArray
        assertFalse(payload[0].jsonObject["workspace"]!!.jsonObject["contents"]!!.jsonObject["server"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("default", payload[0].jsonObject["defaults"]!!.jsonObject["contents"]!!.jsonObject["server"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        Files.writeString(file, "{invalid")
        configuration.reload()
        assertEquals(1, failures.size); assertEquals(1, notifications.size)
        Files.delete(file); configuration.reload()
        assertTrue(configuration.settings.isEmpty())
        assertEquals("editor.tabSize", json(notifications.last().third).jsonArray[1].jsonObject["overrides"]!!.jsonArray.single().jsonArray[1].jsonArray.single().jsonPrimitive.content)
    }

    /** [HostedConfiguration.write], waited for: a refusal is thrown as it is. */
    private fun HostedConfiguration.writeNow(write: Configuration.Write) = runBlocking { write(write) }

    /** Where the commands' activations run; ends with the test. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun cancelScope() = scope.cancel()

    @Test fun `the workbench's own settings have VS Code's defaults, merge with the file's, and may be written`() {
        Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"),
            "{\"files.exclude\": {\"**/.git\": false, \"out\": true}}")
        HostedWorkbench(temp, temp.resolve("state"), emptyList(), { _, _, _ -> "" },
            { message, error -> fail(message, error) }).use { workbench ->
            val configuration = workbench.configuration
            val excluded = configuration.effective["files.exclude"]!!.jsonObject
            assertEquals(JsonPrimitive(false), excluded["**/.git"])
            assertEquals(JsonPrimitive(true), excluded["out"])
            assertEquals(JsonPrimitive(true), excluded["**/.svn"], "A default pattern the file does not mention stays")
            assertEquals(JsonPrimitive("afterDelay"), configuration.effective["files.autoSave"])

            // An extension may write it, as in VS Code, where the workbench declares it.
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[null,"files.autoSave","off",{},null]"""))
            assertEquals(JsonPrimitive("off"), configuration.effective["files.autoSave"])
        }
    }

    /** `app` and `lib`, the folders of `w.code-workspace` in [temp], whose own settings are [workspaceSettings]. */
    private fun multiRoot(workspaceSettings: String = "{}"): CodeBridgeWorkspace {
        Files.createDirectories(temp.resolve("app/.vscode")); Files.createDirectories(temp.resolve("lib"))
        Files.writeString(temp.resolve("w.code-workspace"),
            "{\n  \"folders\": [{\"path\": \"app\"}, {\"path\": \"lib\"}],\n  \"settings\": $workspaceSettings\n}\n")
        WorkspaceChoices.choose(temp, WorkspaceChoices.Choice.File("w.code-workspace"))
        return CodeBridgeWorkspace.of(temp)
    }

    @Test fun `in a multi-root workspace the file's settings are the workspace's, and a folder's apply to it alone`() {
        val layout = multiRoot("{\"probe.mode\": \"workspace\", \"files.exclude\": {\"out\": true}}")
        Files.writeString(temp.resolve("app/.vscode/settings.json"), "{\"probe.mode\": \"app\", \"probe.window\": true, \"files.exclude\": {\"gen\": true}}")
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) },
            initialLayout = layout, folderScoped = setOf("probe.mode", "files.exclude"))

        assertEquals(JsonPrimitive("workspace"), configuration.settings["probe.mode"])
        val app = layout.folders.first { it.name == "app" }
        val lib = layout.folders.first { it.name == "lib" }
        assertEquals(JsonPrimitive("app"), configuration.effectiveFor(app)["probe.mode"])
        assertNull(configuration.effectiveFor(app)["probe.window"], "A window's setting is not a folder's to set")
        assertEquals(setOf("out", "gen"), configuration.effectiveFor(app)["files.exclude"]!!.jsonObject.keys)
        assertEquals(JsonPrimitive("workspace"), configuration.effectiveFor(lib)["probe.mode"])

        val folders = configuration.snapshot()["folders"]!!.jsonArray
        assertEquals(2, folders.size)
        assertEquals("app", folders[0].jsonArray[1].jsonObject["contents"]!!.jsonObject["probe"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test fun `in a multi-root workspace writes go to the workspace file or the folder of the resource`() {
        val layout = multiRoot()
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) },
            setOf("probe.mode", "probe.window"), initialLayout = layout, folderScoped = setOf("probe.mode"))
        val inApp = UriComponents.of(temp.resolve("app/src/main.rs")).toJson()

        configuration.writeNow(Configuration.write(Configuration.UPDATE, """[5,"probe.window",true,{},null]"""))
        assertEquals(JsonPrimitive(true), JsoncEdit.parse(Files.readString(temp.resolve("w.code-workspace")))["settings"]!!.jsonObject["probe.window"])

        configuration.writeNow(Configuration.write(Configuration.UPDATE, """[6,"probe.mode","app",{"resource":$inApp},null]"""))
        assertEquals("{\n  \"probe.mode\": \"app\"\n}\n", Files.readString(temp.resolve("app/.vscode/settings.json")))
        assertEquals(JsonPrimitive("app"), configuration.effectiveFor(layout.folderOf(temp.resolve("app"))!!)["probe.mode"], "Reloaded before the reply")

        // No target, a per-resource setting and a resource: the folder, as in VS Code.
        configuration.writeNow(Configuration.write(Configuration.UPDATE, """[null,"probe.mode","again",{"resource":$inApp},null]"""))
        assertEquals(JsonPrimitive("again"), configuration.folderSettings.getValue(layout.folders.first())["probe.mode"])

        val refused = assertFailsWith<MainThreadException> {
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[6,"probe.window",false,{"resource":$inApp},null]"""))
        }
        assertTrue(refused.message!!.contains("does not support the folder resource scope"), refused.message)
        assertFailsWith<MainThreadException> { configuration.writeNow(Configuration.write(Configuration.UPDATE, """[6,"probe.mode","x",{},null]""")) }
    }

    @Test fun `in a multi-root workspace a search with no folder named looks in every folder`() {
        val layout = multiRoot()
        Files.writeString(temp.resolve("app/a.txt"), "")
        Files.writeString(temp.resolve("lib/b.txt"), "")
        Files.writeString(temp.resolve("outside.txt"), "")
        val workspace = HostedWorkspace(layout) { buildJsonObject {} }

        val found = json(workspace.handle(call("MainThreadWorkspace", $$"$startFileSearch", JsonNull, json("{\"includePattern\":\"**/*.txt\"}")))!!)
            .jsonArray.map { UriComponents.from(it)!!.toPath().fileName.toString() }
        assertEquals(listOf("a.txt", "b.txt"), found)
    }

    @Test fun `the chosen workspace file's folders, edited while the host runs, change its folders, settings and search`() {
        Files.createDirectories(temp.resolve("app")); Files.createDirectories(temp.resolve("lib"))
        Files.writeString(temp.resolve("lib/b.txt"), "")
        val file = Files.writeString(temp.resolve("w.code-workspace"), "{\"folders\": [{\"path\": \"app\"}]}")
        WorkspaceChoices.choose(temp, WorkspaceChoices.Choice.File("w.code-workspace"))
        val workspaceData = LinkedBlockingQueue<JsonArray>()
        HostedWorkbench(temp, temp.resolve("state"), emptyList(), { service, method, args ->
            if (service == "ExtHostWorkspace" && method == $$"$acceptWorkspaceData") workspaceData.add(json(args).jsonArray)
            ""
        }, { message, error -> fail(message, error) }, initialLayout = CodeBridgeWorkspace.of(temp)).use { workbench ->
            workbench.startWatching(); workbench.initialized()
            assertEquals(listOf("app"), workbench.layout.folders.map { it.name })

            // As VS Code takes up an edit to an open workspace's folders: live, `onDidChangeWorkspaceFolders`.
            Files.writeString(file, "{\"folders\": [{\"path\": \"app\"}, {\"path\": \"lib\"}], \"settings\": {\"files.autoSave\": \"off\"}}")

            val data = assertNotNull(workspaceData.poll(10, TimeUnit.SECONDS), "The host is told of the new folders").single().jsonObject
            assertEquals(listOf("app", "lib"), data["folders"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            assertEquals(listOf("app", "lib"), workbench.layout.folders.map { it.name })
            assertEquals(JsonPrimitive("off"), workbench.configuration.effective["files.autoSave"])
        }
    }

    @Test fun `an extension's setting lands in the workspace file, comments intact, and reloads`() {
        // rust-analyzer's "Disable Check on Save" names no target, which in VS Code means this file.
        val file = Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"),
            "{\n  // keep me\n  \"editor.tabSize\": 2\n}\n")
        val changes = mutableListOf<String>()
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) },
            setOf("rust-analyzer.checkOnSave"), changes::add)

        configuration.writeNow(Configuration.write(Configuration.UPDATE, """[null,"rust-analyzer.checkOnSave",false,{},null]"""))

        assertEquals("{\n  // keep me\n  \"editor.tabSize\": 2,\n  \"rust-analyzer.checkOnSave\": false\n}\n", Files.readString(file))
        assertEquals(JsonPrimitive(false), configuration.settings["rust-analyzer.checkOnSave"], "Reloaded before the reply")
        assertEquals($$"ExtHostConfiguration.$acceptConfigurationChanged", notifications.single().let { "${it.first}.${it.second}" })
        assertTrue(changes.single().contains("rust-analyzer.checkOnSave"), "The user is told what changed: $changes")

        configuration.writeNow(Configuration.write(Configuration.REMOVE, """[5,"rust-analyzer.checkOnSave",{},null]"""))
        assertEquals("{\n  // keep me\n  \"editor.tabSize\": 2\n}\n", Files.readString(file))
    }

    @Test fun `without VS Code's files the project's settings are neither read nor written, as with no folder open`() {
        // A teammate's, committed; an extension bundled by a plugin of its own must not see it.
        val committed = Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"),
            "{\"probe.mode\":\"teammate\"}")
        val user = Files.writeString(temp.resolve("user-settings.json"), "{}")
        val configuration = HostedConfiguration(temp, buildJsonObject { put("probe.mode", "default") }, notify,
            { fail(it.toString()) }, userFile = user, vsCodeFiles = false)

        assertTrue(configuration.settings.isEmpty(), "The workspace layer must be empty: ${configuration.settings}")
        // No target is the user's, as VS Code writes it with no workspace; naming the workspace is refused.
        configuration.writeNow(Configuration.write(Configuration.UPDATE, """[null,"probe.mode","mine",{},null]"""))
        assertEquals(JsonPrimitive("mine"), JsoncEdit.parse(Files.readString(user))["probe.mode"])
        val refused = assertFailsWith<MainThreadException> {
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[5,"probe.mode","shared",{},null]"""))
        }
        assertTrue(refused.message!!.contains("no workspace is opened"), refused.message)
        assertEquals("{\"probe.mode\":\"teammate\"}", Files.readString(committed))
    }

    @Test fun `a setting only user settings may set is ignored in the workspace file, and not written there`() {
        Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"),
            "{\"probe.server\":\"/workspace/bin\",\"probe.mode\":\"fast\"}")
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) },
            setOf("probe.server", "probe.mode"), userOnly = setOf("probe.server"))

        // As VS Code reads it: a machine-scoped path in a committed file would run whatever it names.
        assertEquals(setOf("probe.mode"), configuration.settings.keys)
        val refused = assertFailsWith<MainThreadException> {
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[5,"probe.server","/elsewhere",{},null]"""))
        }
        assertTrue(refused.message!!.contains("only into User settings"), refused.message)
    }

    @Test fun `a settings file with unsaved changes is not written`() {
        val file = Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"), "{}")
        val unsaved = object : HostedConfiguration.SettingsFiles by HostedConfiguration.SettingsFiles.DISK {
            override fun dirty(file: Path) = true
        }
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) },
            setOf("probe.known"), files = unsaved)

        // VS Code's refusal: saving the user's half-made edit along with the extension's would be worse.
        val failure = assertFailsWith<MainThreadException> {
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[5,"probe.known",true,{},null]"""))
        }
        assertTrue(failure.message!!.contains("unsaved changes"), "$failure")
        assertEquals("{}", Files.readString(file))
        // And the next write is not held up by it.
        val after = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) }, setOf("probe.known"))
        after.writeNow(Configuration.write(Configuration.UPDATE, """[5,"probe.known",true,{},null]"""))
        assertTrue(Files.readString(file).contains("probe.known"))
    }

    @Test fun `settings the plugin has nowhere to keep are refused, not lost`() {
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) }, setOf("probe.known"))

        val user = assertFailsWith<MainThreadException> {
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[2,"probe.known",true,{},null]"""))
        }
        assertTrue(user.message!!.contains("no user settings"), user.message)
        // VS Code's own refusal for a key no extension declares.
        assertFailsWith<MainThreadException> {
            configuration.writeNow(Configuration.write(Configuration.UPDATE, """[5,"probe.unknown",true,{},null]"""))
        }
        assertFalse(Files.exists(temp.resolve(".vscode/settings.json")), "Nothing was written for either")
    }

    @Test fun `user settings sit beneath the workspace's, and reload on their own`() {
        val userFile = Files.writeString(temp.resolve("user.json"),
            "{\"files.autoSave\":\"afterDelay\",\"editor.tabSize\":8,\"files.exclude\":{\"target\":true}}")
        Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"),
            "{\"editor.tabSize\":2,\"files.exclude\":{\"out\":true}}")
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) }, userFile = userFile)

        val snapshot = configuration.snapshot()
        assertEquals("afterDelay", snapshot["userLocal"]!!.jsonObject["contents"]!!.jsonObject["files"]!!.jsonObject["autoSave"]!!.jsonPrimitive.content,
            "Extensions get the user layer apart, and merge it in the host")
        assertEquals(2, configuration.effective["editor.tabSize"]!!.jsonPrimitive.int, "The workspace wins")
        assertEquals("afterDelay", configuration.effective["files.autoSave"]!!.jsonPrimitive.content)
        assertEquals(setOf("target", "out"), configuration.effective["files.exclude"]!!.jsonObject.keys, "Objects merge, as in VS Code")

        Files.writeString(userFile, "{\"files.autoSave\":\"off\",\"editor.tabSize\":8,\"files.exclude\":{\"target\":true}}")
        configuration.reload()
        assertEquals("files.autoSave", json(notifications.single().third).jsonArray[1].jsonObject["keys"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("off", configuration.effective["files.autoSave"]!!.jsonPrimitive.content)

        Files.writeString(userFile, "{invalid")
        val failures = mutableListOf<Throwable>()
        val tolerant = HostedConfiguration(temp, buildJsonObject {}, notify, failures::add, userFile = userFile)
        assertEquals(1, failures.size, "Reported")
        assertEquals(2, tolerant.effective["editor.tabSize"]!!.jsonPrimitive.int, "And the workspace's settings still apply")
    }

    @Test fun `an empty settings file is no settings, not an error`() {
        // What an editor saves for a file it has just created; at startup an error here stops the workbench.
        val userFile = Files.writeString(temp.resolve("user.json"), "// nothing yet\n")
        val workspaceFile = Files.writeString(Files.createDirectories(temp.resolve(".vscode")).resolve("settings.json"), "")
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) }, userFile = userFile)
        assertEquals(buildJsonObject {}, configuration.effective)

        Files.writeString(workspaceFile, "{\"editor.tabSize\":2}")
        configuration.reload()
        Files.writeString(workspaceFile, "")
        configuration.reload()
        assertEquals(buildJsonObject {}, configuration.effective, "Emptied, the setting is gone")
    }

    @Test fun `an extension's user setting lands in the user file`() {
        val userFile = Files.writeString(temp.resolve("user.json"), "// mine\n{\n}\n")
        val changes = mutableListOf<String>()
        var written = 0
        val configuration = HostedConfiguration(temp, buildJsonObject {}, notify, { fail(it.toString()) },
            setOf("rust-analyzer.checkOnSave"), changes::add, userFile, userWritten = { written++ })

        configuration.writeNow(Configuration.write(Configuration.UPDATE, """[2,"rust-analyzer.checkOnSave",false,{},null]"""))

        assertEquals(JsonPrimitive(false), Json.parseToJsonElement(me.helvert.codebridge.workspace.Jsonc.stripToJson(Files.readString(userFile)))
            .jsonObject["rust-analyzer.checkOnSave"])
        assertTrue(Files.readString(userFile).startsWith("// mine"), "Comments kept")
        assertEquals(JsonPrimitive(false), configuration.user["rust-analyzer.checkOnSave"], "Reloaded before the reply")
        assertEquals(1, written, "Other projects read this file too")
        assertTrue(changes.single().contains("user settings"), changes.single())
        assertFalse(Files.exists(temp.resolve(".vscode/settings.json")))
    }

    @Test fun `a failed command is reported in the extension's own words`() {
        val failure = ExtensionHostSession.ExtensionHostException(
            $$"""{"$isError":true,"name":"Error","message":"Settings writes are not supported yet"}"""
        )

        assertEquals("Settings writes are not supported yet", HostedStatusBar.reason(RuntimeException(failure)))
        assertEquals("plain words", HostedStatusBar.reason(IllegalStateException("plain words")))
    }

    @Test fun `a message with items waits for the user's choice, and answers with its handle`() {
        val messages = HostedMessages()
        val reply = started { messages.handle(call("MainThreadMessageService", $$"$showMessage", JsonPrimitive(1), JsonPrimitive("Reload?"),
            json("{}"), json("""[{"title":"Yes","handle":7,"isCloseAffordance":false},{"title":"No","handle":9,"isCloseAffordance":true}]"""))) }

        val shown = messages.pending.value.single()
        assertEquals(listOf("Yes", "No"), shown.items)
        assertEquals(1, shown.cancelIndex)
        assertFalse(reply.isDone, "The extension waits for the user")

        messages.answer(shown.id, 0)
        messages.answer(shown.id, 1)
        assertEquals("7", reply.get(2, TimeUnit.SECONDS).json, "The first answer wins, as the item's own handle")
        assertTrue(messages.pending.value.isEmpty())
    }

    @Test fun `a plain message is answered at once, and a stopped host dismisses the rest`() {
        val messages = HostedMessages()
        val plain = started { messages.handle(call("MainThreadMessageService", $$"$showMessage", JsonPrimitive(1), JsonPrimitive("rust-analyzer version: 0.3"),
            json("{}"), json("[]"))) }
        val question = started { messages.handle(call("MainThreadMessageService",
            $$"$showMessage", JsonPrimitive(2), JsonPrimitive("Update?"),
            json("{}"), json("""[{"title":"Update","handle":0}]"""))) }

        assertNull(plain.get(2, TimeUnit.SECONDS).json, "Nothing to choose, nothing to wait for")
        assertEquals(2, messages.pending.value.size, "Still waiting to be shown")

        messages.hostStopped()
        assertNull(question.get(2, TimeUnit.SECONDS).json, "Nobody left to ask: dismissed")
        assertTrue(messages.pending.value.isEmpty())
    }

    @Test fun `command activation can register commands before execution completes`() {
        lateinit var commands: HostedCommands
        val activation = CompletableFuture<String>()
        val calls = mutableListOf<Triple<String, String, String>>()
        commands = HostedCommands(scope, listOf(json("{\"publisher\":\"p\",\"name\":\"ext\",\"contributes\":{\"commands\":[{\"command\":\"p.run\"}]}}") as JsonObject),
            { service, method, args ->
                calls.add(Triple(service, method, args))
                if (method == $$"$activate") activation.await() else "42"
            }, { fail(it) })
        val execution = started { commands.handle(call("MainThreadCommands",
            $$"$executeCommand", JsonPrimitive("p.run"), json("[\"argument\",7]"), JsonPrimitive(true))) }
        assertFalse(execution.isDone)
        started { commands.handle(call("MainThreadCommands", $$"$registerCommand", JsonPrimitive("p.run"))) }.join()
        activation.complete("true")
        assertEquals("42", execution.get(2, TimeUnit.SECONDS).json)
        assertEquals("[\"p.run\",\"argument\",7]", calls.last().third)
        started { commands.handle(call("MainThreadCommands", $$"$unregisterCommand", JsonPrimitive("p.run"))) }
        assertEquals("[]", started { commands.handle(call("MainThreadCommands", $$"$getCommands")) }.join().json)
    }

    @Test fun `a workbench command no extension registered is offered to the IDE first`() {
        val asked = mutableListOf<Pair<String, String>>()
        val reported = mutableListOf<String>()
        val commands = HostedCommands(scope, emptyList(), { _, _, _ -> fail("Nothing is sent to the host") }, { reported.add(it) }) { command, arguments ->
            asked.add(command to arguments.toString())
            if (command == "editor.action.showReferences") MainThreadResult() else null
        }
        started { commands.handle(call("MainThreadCommands", $$"$executeCommand",
            JsonPrimitive("editor.action.showReferences"), json("[1,2]"), JsonPrimitive(true))) }.get(2, TimeUnit.SECONDS)
        assertEquals("editor.action.showReferences" to "[1,2]", asked.single())
        assertTrue(reported.isEmpty(), "Done by the IDE, not reported as missing")

        // One the IDE does not do either is still only reported.
        started { commands.handle(call("MainThreadCommands", $$"$executeCommand",
            JsonPrimitive("workbench.action.unknown"), json("[]"), JsonPrimitive(true))) }.get(2, TimeUnit.SECONDS)
        assertEquals(listOf("Workbench command is not implemented: workbench.action.unknown"), reported)
    }

    @Test fun `context keys an extension sets reach the when clauses, and nothing is sent to the host`() {
        val keys = mutableListOf<Pair<String, String?>>()
        val commands = HostedCommands(scope, emptyList(), { _, _, _ -> fail("Nothing is sent to the host") }, { fail("Not reported: $it") },
            contextChanged = { key, value -> keys.add(key to value?.toString()) })
        started { commands.handle(call("MainThreadCommands", $$"$executeCommand",
            JsonPrimitive("setContext"), json("[\"inRustProject\",true]"), JsonPrimitive(true))) }.get(2, TimeUnit.SECONDS)
        started { commands.handle(call("MainThreadCommands", $$"$executeCommand",
            JsonPrimitive("setContext"), json("[\"lldb.showDisassembly\"]"), JsonPrimitive(true))) }.get(2, TimeUnit.SECONDS)
        assertEquals(listOf("inRustProject" to "true", "lldb.showDisassembly" to null), keys)
    }

    @Test fun `console calls are recorded at the severity the extension chose`() {
        val recorded = mutableListOf<Pair<LogLevel, String>>()
        val logging = HostedLogging { level, text -> recorded.add(level to text) }
        for (severity in listOf("log", "warn", "error")) {
            assertTrue(logging.handle(call("MainThreadConsole", $$"$logExtensionHostMessage",
                json("{\"severity\":\"$severity\",\"arguments\":\"[\\\"said something\\\"]\"}"))))
        }
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARNING, LogLevel.ERROR), recorded.map { it.first })
        // Prefixed, because in the IDE log these sit among the plugin's own lines and a reader has
        // to be able to tell an extension's account of itself from ours.
        assertTrue(recorded.all { it.second == "console: said something" })
    }

    @Test fun `a logged batch is named by the extension that registered the file`() {
        val recorded = mutableListOf<Pair<LogLevel, String>>()
        val logging = HostedLogging { level, text -> recorded.add(level to text) }
        val file = temp.resolve("logs/Probe.log")
        logging.handle(call("MainThreadLogger", $$"$registerLogger",
            json("{\"resource\":${uri(file)},\"id\":\"1a\",\"name\":\"Probe\",\"extensionId\":\"me.probe\"}")))
        logging.handle(call("MainThreadLogger", $$"$log", uri(file), json("[[3,\"ready\"]]")))

        assertEquals(LogLevel.INFO, recorded.first().first)
        assertTrue(recorded.first().second.endsWith("is logging to ${UriComponents.of(file).path}"))
        assertEquals(LogLevel.INFO to "Probe: ready", recorded.last())
    }

    @Test fun `the hidden logger behind an output channel is not announced twice`() {
        val recorded = mutableListOf<Pair<LogLevel, String>>()
        val logging = HostedLogging { level, text -> recorded.add(level to text) }
        // Every `window.createOutputChannel` registers one of these, and HostedOutputChannels
        // already reports the channel by name; at info this would double that line for each one.
        logging.handle(call("MainThreadLogger", $$"$registerLogger",
            json("{\"resource\":${uri(temp.resolve("1-Server.log"))},\"id\":\"9f\",\"hidden\":true}")))
        assertEquals(LogLevel.DEBUG, recorded.single().first)
    }

    @Test fun `an extension's own log is offered for showing, and withdrawn with its logger`() {
        // rust-analyzer's "rust-analyzer Extension" log is one of these, and the only place it says
        // why it failed to start; the channel its status item opens is usually empty.
        val opened = mutableListOf<Pair<String, Path>>()
        val closed = mutableListOf<String>()
        val logging = HostedLogging(object : HostedLogging.Files {
            override fun opened(resource: String, name: String, file: Path, extensionId: String?) { opened.add(name to file) }
            override fun closed(resource: String) { closed.add(resource) }
        }) { _, _ -> }
        val file = temp.resolve("logs/Probe.log")

        logging.handle(call("MainThreadLogger", $$"$registerLogger",
            json("{\"resource\":${uri(file)},\"id\":\"1a\",\"name\":\"Probe\",\"extensionId\":\"me.probe\"}")))
        // A hidden logger is the file behind an output channel, which is already shown as that.
        logging.handle(call("MainThreadLogger", $$"$registerLogger",
            json("{\"resource\":${uri(temp.resolve("1-Server.log"))},\"id\":\"9f\",\"hidden\":true}")))

        assertEquals(listOf("Probe" to file), opened)

        logging.handle(call("MainThreadLogger", $$"$deregisterLogger", uri(file)))
        assertEquals(listOf(UriComponents.of(file).uri), closed)
    }

    @Test fun `telemetry is consumed without keeping what the event carried`() {
        val recorded = mutableListOf<Pair<LogLevel, String>>()
        val logging = HostedLogging { level, text -> recorded.add(level to text) }
        assertTrue(logging.handle(call("MainThreadTelemetry", $$"$publicLog2", JsonPrimitive("Extension:ActionExecuted"),
            json("{\"extensionId\":\"me.probe\",\"file\":\"/home/someone/secret.kt\"}"))))

        assertEquals(LogLevel.DEBUG, recorded.single().first)
        assertTrue(recorded.single().second.contains("Extension:ActionExecuted"))
        // The properties are where an extension puts paths, identifiers and error text.
        assertFalse(recorded.single().second.contains("secret.kt"))
    }

    @Test fun `an unknown method is still consumed and a malformed one does not escape`() {
        val recorded = mutableListOf<Pair<LogLevel, String>>()
        val logging = HostedLogging { level, text -> recorded.add(level to text) }
        // Consumed: the host was answered either way, and refusing the service is worse than
        // recording a method nobody has got to yet.
        assertTrue(logging.handle(call("MainThreadLogger", $$"$setLogLevel", JsonPrimitive(2))))
        assertEquals(LogLevel.DEBUG, recorded.single().first)
        assertTrue(logging.handle(MainThreadCall("MainThreadConsole", $$"$logExtensionHostMessage", "not json")))
        assertFalse(logging.handle(call("MainThreadWindow", $$"$getInitialState")))
    }
}
