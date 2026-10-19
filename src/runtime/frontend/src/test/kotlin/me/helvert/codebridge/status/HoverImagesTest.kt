package me.helvert.codebridge.status

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.*
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Images in a status bar hover, which Swing's HTML view cannot load by itself. */
class HoverImagesTest {

    @Test
    fun `a data image is decoded and handed to the pane by an address of its own`() {
        // GitLens's avatar, as the backend leaves it: sized, its source a `data:` URL.
        val png =
            ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }
        val source = "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())
        val html = """<p><img src="$source" alt="You" width="16" height="16" /> You</p>"""

        runBlocking { withTimeout(10.seconds) { HoverImages.fetch(html) } }
        val (resolved, images) = HoverImages.resolve(html)

        assertEquals(1, images.size())
        val address = images.keys().nextElement().toString()
        assertTrue("""<img src="$address" alt="You" width="16" height="16" />""" in resolved, resolved)
        assertEquals(2, images.elements().nextElement().getWidth(null))
    }

    @Test
    fun `an image that cannot be had is left out, not waited for again`() {
        val html = """<img src="data:text/html,nope" /><img src="file:///etc/passwd" />"""

        runBlocking { withTimeout(10.seconds) { HoverImages.fetch(html) } }
        assertTrue(HoverImages.wanted(html).isEmpty(), "Nothing left to fetch")
        assertEquals(0, HoverImages.resolve(html).second.size())
    }
}
