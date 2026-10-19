package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one policy for a payload that is not the shape it should be.
 *
 * This exists because there used not to be one: a missing argument threw
 * `IndexOutOfBoundsException` at one handler, `NoSuchElementException` at another, and quietly
 * became a default at a third -- none of which say that the method's shape has changed under us.
 */
class ArgsTest {

    @Test
    fun `reads arguments by position`() {
        val args = Args.of($$"$register", """["name",{"scheme":"file","path":"/C:/x"},7,true]""")
        assertEquals("name", args.str(0))
        assertEquals("/C:/x", args.uri(1).path)
        assertEquals(7, args.int(2))
        assertTrue(args.bool(3))
        assertEquals(4, args.size)
    }

    @Test
    fun `a missing argument names the method and the position`() {
        val failure = runCatching { Args.of($$"$update", """["only"]""").int(2) }.exceptionOrNull()
        assertTrue(failure is PayloadException, "got $failure")
        val message = failure.message!!
        assertTrue(message.contains($$"$update"), message)
        assertTrue(message.contains("argument 2"), message)
        assertTrue(message.contains("absent"), message)
        assertTrue(message.contains("different VS Code build"), message)
    }

    @Test
    fun `an argument of the wrong type quotes what arrived`() {
        val failure = runCatching { Args.of($$"$reveal", """[{"a":1}]""").str(0) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("""{"a":1}"""), failure.message)
    }

    @Test
    fun `optional arguments read as null rather than failing`() {
        val args = Args.of($$"$update", """["channel"]""")
        assertNull(args.intOrNull(1))
        assertNull(args.longOrNull(2))
        assertNull(args.uriOrNull(1))
        assertEquals(emptyList(), args.stringsOrEmpty(3))
    }

    @Test
    fun `a payload that is not an argument list is refused as one`() {
        assertTrue(runCatching { Args.of($$"$m", "not json") }.exceptionOrNull() is PayloadException)
        assertTrue(runCatching { Args.of($$"$m", """{"a":1}""") }.exceptionOrNull() is PayloadException)
        assertNull(Args.ofOrNull($$"$m", "not json"))
    }
}
