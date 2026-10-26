package me.helvert.codebridge.exthost.protocol.payload

import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Attachment
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Decoration types and `setDecorations`, as the 1.143 host sends them. */
class TextEditorDecorationsTest {

    @Test
    fun `reads a type with theme variants laid over its base`() {
        val (key, type) = TextEditorDecorations.register(
            $$"""["ext.id","TextEditorDecorationType7",{
            "isWholeLine":true,"rangeBehavior":1,
            "backgroundColor":{"id":"ext.lineBackground"},"border":"1px solid","borderColor":"#f00",
            "light":{"backgroundColor":"rgba(0,0,0,0.1)","gutterIconPath":{"$mid":1,"scheme":"file","path":"/x/light.svg"}},
            "dark":{"color":"white"},
            "after":{"contentText":" blame","color":"#999"},
            "before":{"contentText":" ","width":"calc(30ch - 6px)"}
        }]"""
        )
        assertEquals("TextEditorDecorationType7", key)
        assertEquals(true, type.isWholeLine)
        assertEquals(1, type.rangeBehavior)
        assertEquals(Color.Css("rgba(0,0,0,0.1)"), type.inLight.backgroundColor)
        assertEquals(Color.Theme("ext.lineBackground"), type.inDark.backgroundColor)
        assertEquals(Color.Css("white"), type.inDark.color)
        assertNull(type.inLight.color)
        assertEquals("/x/light.svg", type.inLight.gutterIconPath?.path)
        assertNull(type.inDark.gutterIconPath)
        assertEquals(Color.Css("#f00"), type.inDark.borderColor)
        assertEquals(Attachment(contentText = " blame", color = Color.Css("#999")), type.inDark.after)
        // GitLens' blame column: as wide on a commit's later lines, a space each, as on its first.
        assertEquals("calc(30ch - 6px)", type.inDark.before?.width)
    }

    @Test
    fun `an empty type grows at both edges and is not whole-line`() {
        val (_, type) = TextEditorDecorations.register("""["ext","k",{}]""")
        assertEquals(false, type.isWholeLine)
        assertEquals(0, type.rangeBehavior)
        assertEquals(TextEditorDecorations.Style(), type.inLight)
    }

    @Test
    fun `reads decorations with hovers and their own render options`() {
        val set = TextEditorDecorations.set(
            TextEditorDecorations.SET, """["editor-1","k",[
            {"range":{"startLineNumber":2,"startColumn":1,"endLineNumber":2,"endColumn":5},
             "hoverMessage":[{"value":"**one**","isTrusted":false},"two"],
             "renderOptions":{"after":{"contentText":"x"},"dark":{"after":{"contentText":"y"}}}},
            {"range":{"startLineNumber":3,"startColumn":1,"endLineNumber":3,"endColumn":1},"hoverMessage":{"value":"three"}}
        ]]"""
        )
        assertEquals("editor-1", set.editorId)
        assertEquals("k", set.key)
        val (first, second) = set.decorations
        assertEquals(Range(2, 1, 2, 5), first.range)
        assertEquals(listOf("**one**", "two"), first.hoverMessages.map { Markdown.text(it) })
        assertEquals("x", first.renderOptions?.base?.after?.contentText)
        assertEquals("y", first.renderOptions?.dark?.after?.contentText)
        assertEquals(listOf("three"), second.hoverMessages.map { Markdown.text(it) })
        assertNull(second.renderOptions)
    }

    @Test
    fun `reads the fast form as four numbers per range`() {
        val set = TextEditorDecorations.set(TextEditorDecorations.SET_FAST, """["e","k",[1,2,1,4,5,1,6,1]]""")
        assertEquals(listOf(Range(1, 2, 1, 4), Range(5, 1, 6, 1)), set.decorations.map { it.range })
        assertEquals(
            emptyList(),
            TextEditorDecorations.set(TextEditorDecorations.SET_FAST, """["e","k",[]]""").decorations
        )
    }

    @Test
    fun `reads the key a removal names`() {
        assertEquals("k", TextEditorDecorations.removed("""["k"]"""))
    }
}
