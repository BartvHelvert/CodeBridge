package me.helvert.codebridge.webview

import me.helvert.codebridge.rpc.TreeViewDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a webview view's page is asked for again: once a provider has registered since the last one. */
class WebviewViewsTest {
    private val view = TreeViewDto("cats.view", "Cats", "me.cats", "cats", "Cats", webview = true)

    @Test
    fun `a provider registering is one registered anew`() {
        assertTrue(registeredAnew(view, view.copy(registered = true)))
    }

    @Test
    fun `a provider replaced by another host's is one registered anew, though its loss was never seen`() {
        // Lost and registered again, the update in between merged away on its way to the frontend.
        val before = view.copy(registered = true, generation = 3)
        assertTrue(registeredAnew(before, before.copy(generation = 4)))
    }

    @Test
    fun `the same provider changing its title is not one registered anew`() {
        val before = view.copy(registered = true, generation = 3)
        assertFalse(registeredAnew(before, before.copy(title = "Kittens")))
    }

    @Test
    fun `a provider lost is not one registered anew`() {
        val before = view.copy(registered = true, generation = 3)
        assertFalse(registeredAnew(before, before.copy(registered = false, generation = 4)))
    }
}
