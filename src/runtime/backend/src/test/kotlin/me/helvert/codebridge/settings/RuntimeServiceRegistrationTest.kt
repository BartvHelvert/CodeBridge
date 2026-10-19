package me.helvert.codebridge.settings

import me.helvert.codebridge.ide.EditorFixture
import org.junit.jupiter.api.Test
import kotlin.test.assertSame

/**
 * Both runtime classes are light services declared in the `shared` module rather than in the module
 * that uses them, so that the tests can reach the same resolution the IDE uses. A light service in a module the plugin does not load registers nothing, and
 * [VsCodeRuntime.getInstance] would then quietly hand back a plain instance forever -- correct, since
 * neither class holds state, and impossible to notice.
 *
 * This is the assertion that would fail instead.
 */
class RuntimeServiceRegistrationTest : EditorFixture() {

    @Test
    fun `the runtime services register from the shared module`() = onEdt {
        assertSame(VsCodeRuntime.getInstance(), VsCodeRuntime.getInstance())
        assertSame(BundledRuntime.getInstance(), BundledRuntime.getInstance())
    }
}
