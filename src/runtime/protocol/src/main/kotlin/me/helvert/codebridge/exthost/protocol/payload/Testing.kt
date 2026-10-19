package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `MainThreadTesting` / `ExtHostTesting`: the `vscode.tests` API.
 *
 * An extension's `TestController` is mirrored here as a stream of diffs ([PUBLISH_DIFF]) over a tree
 * of items, each named by a [TestId]: the controller's id and every ancestor's local id, joined by
 * NUL. The host sends a diff 200ms after the tree last changed, and only for what the extension has
 * discovered; asking it to discover more is [expand], which runs the controller's `resolveHandler`.
 *
 * A run the workbench starts ([runControllerTests]) carries a run id of the workbench's choosing,
 * and the host reports back under it: a task per `createTestRun` ([STARTED_TASK]), the items it
 * touches ([ADD_TESTS], always with their ancestors), their states, messages and output, and the
 * task's end. A run the extension starts on its own announces itself first ([STARTED_EXTENSION_RUN])
 * under an id of its own, and ends with [FINISHED_EXTENSION_RUN].
 *
 * Read off the pinned runtime: `ExtHostTesting`, `TestRunCoordinator` and the `ITestItem`,
 * `TestsDiffOp` and `ITestMessage` serializers in `testTypes`.
 */
object Testing {

    const val SERVICE: String = "MainThreadTesting"
    const val EXT_HOST: String = "ExtHostTesting"

    const val REGISTER_CONTROLLER: String = $$"$registerTestController"
    const val UPDATE_CONTROLLER: String = $$"$updateController"
    const val UNREGISTER_CONTROLLER: String = $$"$unregisterTestController"
    const val PUBLISH_DIFF: String = $$"$publishDiff"
    const val PUBLISH_PROFILE: String = $$"$publishTestRunProfile"
    const val UPDATE_PROFILE: String = $$"$updateTestRunConfig"
    const val REMOVE_PROFILE: String = $$"$removeTestProfile"
    const val STARTED_EXTENSION_RUN: String = $$"$startedExtensionTestRun"
    const val FINISHED_EXTENSION_RUN: String = $$"$finishedExtensionTestRun"
    const val STARTED_TASK: String = $$"$startedTestRunTask"
    const val FINISHED_TASK: String = $$"$finishedTestRunTask"
    const val ADD_TESTS: String = $$"$addTestsToRun"
    const val UPDATE_STATE: String = $$"$updateTestStateInRun"
    const val APPEND_MESSAGES: String = $$"$appendTestMessagesInRun"
    const val APPEND_OUTPUT: String = $$"$appendOutputToRun"
    /** `vscode.tests.runTests`: an extension asking the workbench to run tests, as a user would. */
    const val RUN_TESTS: String = $$"$runTests"

    const val EXPAND: String = $$"$expandTest"
    const val RUN_CONTROLLER_TESTS: String = $$"$runControllerTests"
    const val CANCEL_RUN: String = $$"$cancelExtensionTestRun"

    /** `TestResultState`. */
    enum class State(val wire: Int) {
        UNSET(0), QUEUED(1), RUNNING(2), PASSED(3), FAILED(4), SKIPPED(5), ERRORED(6);

        companion object {
            fun of(wire: Int?): State = entries.firstOrNull { it.wire == wire } ?: UNSET
        }
    }

    /** `TestRunProfileBitset`, which a profile's `group` is one bit of. Coverage is not run here. */
    enum class Group(val wire: Int) {
        RUN(2), DEBUG(4), COVERAGE(8);

        companion object {
            fun of(wire: Int?): Group? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** `TestItemExpandState`: whether [expand] would discover anything under an item. */
    enum class Expand(val wire: Int) {
        NOT_EXPANDABLE(0), EXPANDABLE(1), BUSY_EXPANDING(2), EXPANDED(3);

        companion object {
            fun of(wire: Int?): Expand = entries.firstOrNull { it.wire == wire } ?: NOT_EXPANDABLE
        }
    }

    /** A `TestId` as a string: the controller id, then each ancestor's id, NUL-separated. */
    object TestId {
        const val DELIMITER: Char = '\u0000'

        fun isRoot(id: String): Boolean = DELIMITER !in id
        fun controllerId(id: String): String = id.substringBefore(DELIMITER)
        fun parent(id: String): String? = id.lastIndexOf(DELIMITER).takeIf { it >= 0 }?.let { id.substring(0, it) }
        fun localId(id: String): String = id.substringAfterLast(DELIMITER)
        fun join(vararg path: String): String = path.joinToString(DELIMITER.toString())
    }

    /**
     * `ITestItem`, as `ITestItem.serialize` writes it into a diff and `TestItem.from` into
     * [ADD_TESTS]. [raw] is kept, since an update carries only the fields that changed.
     */
    data class Item(
        val extId: String,
        val label: String,
        val uri: UriComponents?,
        val range: Range?,
        val description: String?,
        val error: String?,
        val sortText: String?,
        val tags: List<String>,
        val busy: Boolean,
        val raw: JsonObject,
    ) {
        /** This item with [update]'s fields put over it, as the workbench's `applyTestItemUpdate` does. */
        fun updated(update: JsonObject): Item = item(JsonObject(raw + update))
    }

    fun item(json: JsonObject): Item = Item(
        extId = json.string("extId") ?: throw PayloadException("A test item has no extId: $json"),
        label = json.string("label") ?: "",
        uri = UriComponents.from(json["uri"]),
        range = Range.from(json["range"]),
        description = json.string("description"),
        error = Markdown.text(json["error"]?.takeUnless { it is JsonNull }),
        sortText = json.string("sortText"),
        tags = (json["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        busy = (json["busy"] as? JsonPrimitive)?.booleanOrNull ?: false,
        raw = json,
    )

    /** One `TestsDiffOp`. Document syncs, retirement, pending-host counts and tags are [Other]. */
    sealed interface DiffOp {
        data class Add(val expand: Expand, val item: Item) : DiffOp
        /** [item] holds only the fields that changed, and is null when only [expand] did. */
        data class Update(val extId: String, val expand: Expand?, val item: JsonObject?) : DiffOp
        /** Removes the item and everything under it. */
        data class Remove(val extId: String) : DiffOp
        data class Other(val op: Int) : DiffOp
    }

    /** `$publishDiff(controllerId, diff)`. */
    data class Diff(val controllerId: String, val ops: List<DiffOp>)

    fun diff(argsJson: String): Diff {
        val args = Args.of(PUBLISH_DIFF, argsJson)
        return Diff(args.str(0), args.array(1).mapNotNull { (it as? JsonObject)?.let(::diffOp) })
    }

    private fun diffOp(json: JsonObject): DiffOp = when (val op = json.int("op")) {
        0 -> {
            val added = json["item"] as? JsonObject ?: throw PayloadException("An added test has no item: $json")
            DiffOp.Add(Expand.of(added.int("expand")), item(added["item"] as? JsonObject ?: throw PayloadException("An added test has no item: $json")))
        }
        1 -> {
            val update = json["item"] as? JsonObject ?: throw PayloadException("A test update has no item: $json")
            DiffOp.Update(
                extId = update.string("extId") ?: throw PayloadException("A test update has no extId: $json"),
                expand = update.int("expand")?.let(Expand::of),
                item = update["item"] as? JsonObject,
            )
        }
        3 -> DiffOp.Remove(json.string("itemId") ?: throw PayloadException("A test removal has no itemId: $json"))
        else -> DiffOp.Other(op ?: -1)
    }

    /** `$registerTestController(controllerId, label, capabilities)`. */
    data class Controller(val id: String, val label: String)

    fun controller(argsJson: String): Controller {
        val args = Args.of(REGISTER_CONTROLLER, argsJson)
        return Controller(args.str(0), args.strOrNull(1) ?: args.str(0))
    }

    /** `$updateController(controllerId, {label?, capabilities?})`: the new label, if it changed. */
    fun controllerLabel(argsJson: String): Pair<String, String?> {
        val args = Args.of(UPDATE_CONTROLLER, argsJson)
        return args.str(0) to args.objOrNull(1)?.string("label")
    }

    /**
     * `ITestRunProfile`. A profile with a [tag] runs only the items carrying it. [isDefault] is which
     * of a controller's profiles in a group runs when nothing more specific is asked for.
     */
    data class Profile(
        val controllerId: String,
        val profileId: Int,
        val label: String,
        val group: Group?,
        val isDefault: Boolean,
        val tag: String?,
        val raw: JsonObject,
    ) {
        fun updated(update: JsonObject): Profile = profile(JsonObject(raw + update))
    }

    fun profile(json: JsonObject): Profile = Profile(
        controllerId = json.string("controllerId") ?: throw PayloadException("A test profile has no controller: $json"),
        profileId = json.int("profileId") ?: throw PayloadException("A test profile has no id: $json"),
        label = json.string("label") ?: "",
        group = Group.of(json.int("group")),
        isDefault = (json["isDefault"] as? JsonPrimitive)?.booleanOrNull ?: false,
        tag = json.string("tag"),
        raw = json,
    )

    /** `$publishTestRunProfile(profile)`. */
    fun publishedProfile(argsJson: String): Profile = profile(Args.of(PUBLISH_PROFILE, argsJson).obj(0))

    /** `$updateTestRunConfig(controllerId, profileId, update)`. */
    data class ProfileUpdate(val controllerId: String, val profileId: Int, val update: JsonObject)

    fun profileUpdate(argsJson: String): ProfileUpdate {
        val args = Args.of(UPDATE_PROFILE, argsJson)
        return ProfileUpdate(args.str(0), args.int(1), args.obj(2))
    }

    /** `$startedExtensionTestRun(dto)`: a run the extension started, not one asked of it. */
    data class ExtensionRun(val runId: String, val controllerId: String, val group: Group?, val include: List<String>)

    fun extensionRun(argsJson: String): ExtensionRun {
        val dto = Args.of(STARTED_EXTENSION_RUN, argsJson).obj(0)
        return ExtensionRun(
            runId = dto.string("id") ?: throw PayloadException("An extension's test run has no id: $dto"),
            controllerId = dto.string("controllerId") ?: throw PayloadException("An extension's test run has no controller: $dto"),
            group = Group.of((dto["profile"] as? JsonObject)?.int("group")),
            include = (dto["include"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** `$startedTestRunTask(runId, {id, ctrlId, name, running})`: one `createTestRun`. */
    data class Task(val runId: String, val taskId: String, val name: String)

    fun startedTask(argsJson: String): Task {
        val args = Args.of(STARTED_TASK, argsJson)
        val task = args.obj(1)
        return Task(args.str(0), task.string("id") ?: throw PayloadException("A test run task has no id: $task"), task.string("name") ?: "")
    }

    /** `$addTestsToRun(controllerId, runId, items)`: an item and the ancestors not yet sent, root first. */
    data class AddedTests(val runId: String, val items: List<Item>)

    fun addedTests(argsJson: String): AddedTests {
        val args = Args.of(ADD_TESTS, argsJson)
        return AddedTests(args.str(1), args.array(2).mapNotNull { (it as? JsonObject)?.let(::item) })
    }

    /** `$updateTestStateInRun(runId, taskId, testId, state, duration?)`; [duration] in milliseconds. */
    data class StateUpdate(val runId: String, val taskId: String, val testId: String, val state: State, val duration: Double?)

    fun stateUpdate(argsJson: String): StateUpdate {
        val args = Args.of(UPDATE_STATE, argsJson)
        return StateUpdate(args.str(0), args.str(1), args.str(2), State.of(args.intOrNull(3)), args.doubleOrNull(4))
    }

    /** A frame of a message's `stackTrace`. */
    data class Frame(val label: String, val uri: UriComponents?, val position: Position?)

    /**
     * `ITestErrorMessage`, which every `TestMessage` an extension reports becomes. [expected] and
     * [actual] are both set for a comparison that can be shown as a diff.
     */
    data class Message(
        val text: String,
        val expected: String?,
        val actual: String?,
        val uri: UriComponents?,
        val range: Range?,
        val stackTrace: List<Frame>,
    )

    /** `$appendTestMessagesInRun(runId, taskId, testId, messages)`. */
    data class Messages(val runId: String, val taskId: String, val testId: String, val messages: List<Message>)

    fun messages(argsJson: String): Messages {
        val args = Args.of(APPEND_MESSAGES, argsJson)
        return Messages(args.str(0), args.str(1), args.str(2), args.array(3).mapNotNull { (it as? JsonObject)?.let(::message) })
    }

    private fun message(json: JsonObject): Message {
        val location = json["location"] as? JsonObject
        return Message(
            text = Markdown.text(json["message"]) ?: "",
            expected = json.string("expected"),
            actual = json.string("actual"),
            uri = UriComponents.from(location?.get("uri")),
            range = Range.from(location?.get("range")),
            stackTrace = (json["stackTrace"] as? JsonArray).orEmpty().mapNotNull { frame ->
                (frame as? JsonObject)?.let {
                    Frame(it.string("label") ?: "", UriComponents.from(it["uri"]), position(it["position"]))
                }
            },
        )
    }

    private fun position(element: JsonElement?): Position? {
        val json = element as? JsonObject ?: return null
        return Position(json.int("lineNumber") ?: return null, json.int("column") ?: 1)
    }

    /**
     * `$appendOutputToRun(runId, taskId, output, location?, testId?)`. The output is a `VSBuffer`,
     * which arrives beside the JSON rather than in it: the `bytes` passed is that buffer. Terminal text, with
     * `\r\n` line ends and ANSI colours, as `TestRun.appendOutput` documents.
     */
    data class Output(val runId: String, val taskId: String, val text: String, val testId: String?)

    fun output(argsJson: String, bytes: ByteArray?): Output {
        val args = Args.of(APPEND_OUTPUT, argsJson)
        return Output(args.str(0), args.str(1), bytes?.toString(Charsets.UTF_8) ?: args.strOrNull(2).orEmpty(), args.strOrNull(4))
    }

    /** The argument index of [APPEND_OUTPUT]'s buffer. */
    const val OUTPUT_BUFFER: Int = 2

    /** `vscode.tests.runTests`'s request: `{group, targets: [{controllerId, profileId, testIds}], exclude}`. */
    data class RunRequest(val group: Group, val targets: List<Target>, val exclude: List<String>)

    data class Target(val controllerId: String, val profileId: Int, val testIds: List<String>)

    fun runRequest(argsJson: String): RunRequest {
        val dto = Args.of(RUN_TESTS, argsJson).obj(0)
        return RunRequest(
            group = Group.of(dto.int("group")) ?: Group.RUN,
            targets = (dto["targets"] as? JsonArray).orEmpty().mapNotNull { target ->
                val json = target as? JsonObject ?: return@mapNotNull null
                Target(
                    controllerId = json.string("controllerId") ?: return@mapNotNull null,
                    profileId = json.int("profileId") ?: return@mapNotNull null,
                    testIds = (json["testIds"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            },
            exclude = (dto["exclude"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** `$expandTest(testId, levels)`; a negative [levels] discovers everything under the item. */
    fun expand(testId: String, levels: Int): String = buildJsonArray { add(testId); add(levels) }.toString()

    /** One `ExtensionRunTestsRequest`, as [runControllerTests] sends it. */
    data class ControllerRun(
        val runId: String,
        val controllerId: String,
        val profileId: Int,
        val testIds: List<String>,
        val excludeExtIds: List<String> = emptyList(),
    )

    /**
     * `$runControllerTests(requests, token)`. Sent cancellable: the host appends the token itself.
     * Answered once the profile's `runHandler` has returned, with one `{error?}` per request.
     */
    fun runControllerTests(requests: List<ControllerRun>): String = buildJsonArray {
        add(buildJsonArray {
            for ((runId, controllerId, profileId, testIds, excludeExtIds) in requests) add(buildJsonObject {
                put("runId", runId)
                put("controllerId", controllerId)
                put("profileId", profileId)
                putJsonArray("testIds") { testIds.forEach { add(it) } }
                putJsonArray("excludeExtIds") { excludeExtIds.forEach { add(it) } }
            })
        })
    }.toString()

    /** What each request's `runHandler` threw, if it did. */
    fun runErrors(reply: String): List<String> =
        (runCatching { Json.parseToJsonElement(reply) }.getOrNull() as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("error") }

    /**
     * `$cancelExtensionTestRun(runId, taskId?)`. Cancels the run's token the first time; asked again,
     * the host ends every task that is still open.
     */
    fun cancelRun(runId: String): String = buildJsonArray { add(runId) }.toString()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
}
