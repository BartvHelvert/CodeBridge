package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `MainThreadDecorations` calls and `$provideDecorations` replies, as the host sends them. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class DecorationsTest {

    @Test
    fun `reads registrations and changes`() {
        assertEquals(3 to "vscode.git", Decorations.registration("""[3,"vscode.git"]"""))
        assertEquals(3, Decorations.unregistered("[3]"))
        val (handle, resources) = Decorations.changed($$"""[3,[{"$mid":1,"scheme":"file","path":"/w/a.rs"}]]""")
        assertEquals(3, handle)
        assertEquals(listOf("/w/a.rs"), resources?.map { it.path })
        assertNull(Decorations.changed("[3,null]").second, "Everything changed")
    }

    @Test
    fun `asks about each resource by its index`() {
        val a = UriComponents("file", "", "/w/a.rs", "", "")
        val b = UriComponents("file", "", "/w/b.rs", "", "")
        assertEquals("""[7,[{"id":0,"uri":${a.toJson()}},{"id":1,"uri":${b.toJson()}}]]""", Decorations.provide(7, listOf(a, b)))
    }

    @Test
    fun `reads decorations by index, leaving out codicon badges`() {
        val decorations = Decorations.decorations("""{
            "0":[true,"Modified","M",{"id":"gitDecoration.modifiedResourceForeground"}],
            "2":[null,"",{"id":"error"},null]
        }""")
        assertEquals(Decorations.Decoration(true, "Modified", "M", "gitDecoration.modifiedResourceForeground"), decorations[0])
        assertEquals(Decorations.Decoration(), decorations[2])
        assertEquals(emptyMap(), Decorations.decorations("{}"))
        assertEquals(emptyMap(), Decorations.decorations(null))
    }
}
