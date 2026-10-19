package me.helvert.codebridge.editors

import kotlin.test.Test
import kotlin.test.assertEquals

/** The error stripe marks of a decoration type: one per run of adjacent lines, as VS Code's ruler bands them. */
class LineRunsTest {

    @Test
    fun `adjacent and overlapping lines are one run, a gap starts another`() {
        // GitLens' highlight: a decoration per line of the commit, with a line of another between.
        assertEquals(listOf(0 to 2, 4 to 7), lineRuns(listOf(1 to 1, 0 to 0, 2 to 2, 4 to 5, 5 to 7)))
    }

    @Test
    fun `nothing is no runs`() {
        assertEquals(emptyList(), lineRuns(emptyList()))
    }
}
