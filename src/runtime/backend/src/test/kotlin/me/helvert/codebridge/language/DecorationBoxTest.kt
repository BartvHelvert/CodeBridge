package me.helvert.codebridge.language

import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Attachment
import me.helvert.codebridge.exthost.protocol.payload.TextEditorDecorations.Color as CssColor
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a decoration's text is drawn inside: the border and top line extensions write as CSS. */
class DecorationBoxTest {

    @Test
    fun `GitLens' blame column - a line between commits, and a heatmap edge coloured per line`() {
        val box = DecorationStyles.box(
            Attachment(
                contentText = " ",
                borderColor = CssColor.Css("#ff8000"),
                textDecoration = "none;text-decoration:overline solid rgba(0, 0, 0, .2);box-sizing:border-box;" +
                        "border-style:solid;border-width:0 2px 0 0;white-space:pre",
            )
        )
        assertEquals("0 2px 0 0", box.borderWidth)
        assertEquals(Color(0xff, 0x80, 0x00).rgb, box.borderColor?.rgb)
        assertEquals(Color(0, 0, 0, 51).rgb, box.overline?.rgb)
    }

    @Test
    fun `a border shorthand, and nothing without a style or a colour`() {
        val box = DecorationStyles.box(Attachment(border = "1px solid #00ff00"))
        assertEquals("1px", box.borderWidth)
        assertEquals(Color.GREEN.rgb, box.borderColor?.rgb)
        assertNull(box.overline)

        // A width with no style draws nothing, as in CSS; a style with no colour has nothing to draw in.
        assertNull(
            DecorationStyles.box(
                Attachment(
                    textDecoration = "none;border-width:2px",
                    borderColor = CssColor.Css("red")
                )
            ).borderWidth
        )
        assertNull(DecorationStyles.box(Attachment(textDecoration = "none;border-style:solid")).borderWidth)
        assertNull(DecorationStyles.box(Attachment(textDecoration = "underline")).overline)
    }
}
