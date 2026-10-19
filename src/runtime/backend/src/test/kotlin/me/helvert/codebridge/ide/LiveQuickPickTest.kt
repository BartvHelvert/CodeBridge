package me.helvert.codebridge.ide

import me.helvert.codebridge.exthost.MainThreadCall
import me.helvert.codebridge.rpc.QuickInputDto
import me.helvert.codebridge.rpc.QuickInputEventDto
import me.helvert.codebridge.started
import me.helvert.codebridge.workbench.HostedQuickInputs
import me.helvert.codebridge.workbench.HostedQuickOpen
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A picker an extension creates and drives itself, as GitLens's commit search does: on screen while
 * the extension changes it, open past a choice, and closed by the extension or the user.
 *
 * What matters is that the extension hears what VS Code would tell it, in order -- a choice that
 * closed the picker, or a value the user typed that never reached it, leaves it waiting on a step
 * the user cannot see.
 */
class LiveQuickPickTest : EditorFixture() {

    private val told = mutableListOf<String>()
    private val quickOpen by lazy {
        HostedQuickOpen(fixture.project) { _, method, args ->
            told += "$method $args"
            "null"
        }
    }

    /** A light fixture shares its project between tests, and with it the questions on screen. */
    @AfterEach
    fun clear() = quickOpen.close()

    @Test
    fun `a choice is told to the extension and leaves the picker open`() = onEdt {
        create(items = OPERATORS)
        val question = shown()
        assertTrue(question.live)
        assertEquals(listOf("Search by Message", "Search by Author"), question.items.map { it.label })

        user(QuickInputEventDto(QuickInputEventDto.ACCEPT, chosen = listOf(1), itemsRevision = question.itemsRevision))

        assertEquals(
            listOf($$"$onDidChangeActive [1,[11]]", $$"$onDidChangeSelection [1,[11]]", $$"$onDidAccept [1]"),
            told,
        )
        assertEquals(question.id, shown().id, "Still on screen: only the extension closes it")
    }

    @Test
    fun `what the extension changes comes in place, and a value it sets is told as one`() = onEdt {
        create(items = OPERATORS)
        val first = shown()

        update(""""value":"message:","title":"Commit Search","activeItems":[11]""")

        val second = shown()
        assertEquals(first.id, second.id)
        assertEquals("message:", second.value)
        assertEquals("Commit Search", second.title)
        assertEquals(1, second.active)
        assertTrue(second.valueRevision > first.valueRevision)

        // The user types on; the value is the user's now, without counting as the extension's.
        user(QuickInputEventDto(QuickInputEventDto.VALUE, value = "message:fix"))
        assertEquals(listOf($$"$onDidChangeValue [1,\"message:fix\"]"), told)
        update(""""items":[{"handle":0,"label":"Search for","description":"message:fix"}]""")
        val third = shown()
        assertEquals("message:fix", third.value)
        assertEquals(second.valueRevision, third.valueRevision)
        assertEquals(null, third.active, "The active item named one of the items replaced")
    }

    @Test
    fun `a choice from items the extension has replaced since is dropped`() = onEdt {
        create(items = OPERATORS)
        val stale = shown().itemsRevision
        update(""""items":[{"handle":0,"label":"Search for","description":"fix"}]""")

        user(QuickInputEventDto(QuickInputEventDto.ACCEPT, chosen = listOf(1), itemsRevision = stale))

        assertEquals(emptyList(), told)
    }

    @Test
    fun `the extension's hide takes it off screen, and its next show brings it back`() = onEdt {
        create(items = OPERATORS)
        update(""""visible":false""")
        assertTrue(inputs.pending.value.isEmpty())
        assertEquals(listOf($$"$onDidHide [1]"), told)

        update(""""visible":true""")
        assertEquals(1, inputs.pending.value.size)
    }

    @Test
    fun `an input box the extension disposes is taken off screen, as GitLens's sign-in code box is`() = onEdt {
        send(QUICK_PICK, """[{"id":1,"type":"inputBox","visible":true,"title":"GitLens Sign In"}]""")
        assertEquals(QuickInputDto.INPUT, shown().kind)

        send($$"$dispose", "[1]")

        assertTrue(inputs.pending.value.isEmpty())
        assertEquals(emptyList(), told, "Disposed: there is no one left to tell it was hidden")
    }

    @Test
    fun `an input box the extension hides is taken off screen and told hidden once`() = onEdt {
        send(QUICK_PICK, """[{"id":1,"type":"inputBox","visible":true}]""")
        update(""""visible":false""")

        assertTrue(inputs.pending.value.isEmpty())
        assertEquals(listOf($$"$onDidHide [1]"), told)
    }

    @Test
    fun `the user closing it is told once, and only a show brings it back`() = onEdt {
        create(items = OPERATORS)
        user(QuickInputEventDto(QuickInputEventDto.HIDE))
        assertTrue(inputs.pending.value.isEmpty())

        // A change the extension makes meanwhile does not reopen it.
        update(""""title":"Commit Search"""")
        assertTrue(inputs.pending.value.isEmpty())
        assertEquals(listOf($$"$onDidHide [1]"), told)

        update(""""visible":true""")
        assertEquals(1, inputs.pending.value.size)
    }

    @Test
    fun `a busy picker is shown before its items, and takes them when they come`() = onEdt {
        create(items = "[]", extra = ""","busy":true""")
        assertTrue(shown().busy)

        update(""""busy":false,"items":$OPERATORS""")

        assertEquals(2, shown().items.size)
        assertEquals(false, shown().busy)
    }

    private fun create(items: String, extra: String = "") =
        send(QUICK_PICK, """[{"id":1,"type":"quickPick","visible":true,"items":$items$extra}]""")

    private fun update(fields: String) = send(QUICK_PICK, """[{"id":1,$fields}]""")

    private fun send(method: String, args: String) {
        started { quickOpen.handle(MainThreadCall("MainThreadQuickOpen", method, args)) }.get()
    }

    private fun user(event: QuickInputEventDto) = inputs.changed(shown().id, event)

    private fun shown(): QuickInputDto = inputs.pending.value.single()

    private val inputs get() = HostedQuickInputs.getInstance(fixture.project)

    private companion object {
        const val QUICK_PICK = $$"$createOrUpdate"
        const val OPERATORS =
            """[{"handle":10,"label":"Search by Message","alwaysShow":true},{"handle":11,"label":"Search by Author","alwaysShow":true}]"""
    }
}
