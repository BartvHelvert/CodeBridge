package me.helvert.codebridge.views

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewStackWeightsTest {
    @Test
    fun `collapsing middle section keeps its header position and expansion restores sizes`() {
        val weights = listOf(2.0, 3.0, 5.0)
        val collapsed = toggledViewStackWeights(weights, listOf(true, true, true), 1)
        assertEquals(listOf(2.0, 3.0, 8.0), collapsed)
        // With the same total visible weight, the body above the clicked header stays the same height.
        assertEquals(weights.sum(), collapsed[0] + collapsed[2])
        assertEquals(weights, toggledViewStackWeights(collapsed, listOf(true, false, true), 1))
    }

    @Test
    fun `space goes to the first open section below skipping collapsed sections`() {
        assertEquals(
            listOf(2.0, 3.0, 4.0, 8.0),
            toggledViewStackWeights(listOf(2.0, 3.0, 4.0, 5.0), listOf(true, true, false, true), 1),
        )
    }

    @Test
    fun `last open section falls back to the nearest open section above`() {
        val weights = listOf(2.0, 3.0, 5.0)
        val collapsed = toggledViewStackWeights(weights, listOf(true, true, true), 2)
        assertEquals(listOf(2.0, 8.0, 5.0), collapsed)
        assertEquals(weights, toggledViewStackWeights(collapsed, listOf(true, true, false), 2))
    }

    @Test
    fun `expanding after neighbor shrinks keeps both heights positive and other sections stable`() {
        val expanded = toggledViewStackWeights(listOf(8.0, 3.0, 2.0), listOf(true, false, true), 1)
        assertEquals(listOf(8.0, 1.0, 1.0), expanded)
        assertTrue(expanded.all { it > 0 })
        assertEquals(10.0, expanded.sum())
    }

    @Test
    fun `toggling with no other open section preserves remembered sizes`() {
        val weights = listOf(2.0, 3.0)
        assertEquals(weights, toggledViewStackWeights(weights, listOf(false, true), 1))
        assertEquals(weights, toggledViewStackWeights(weights, listOf(false, false), 1))
    }
}
