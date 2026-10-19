package me.helvert.codebridge.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.icons.toStrokeIcon
import me.helvert.codebridge.rpc.ContributedIconsDto
import me.helvert.codebridge.rpc.IconFontDto
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.test.*

/** Icons extensions contribute, as `$(name)` finds them. */
@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class ContributedIconsTest {

    // Headless -- a Linux CI runner -- the platform draws every icon as one black pixel unless told
    // otherwise, and these tests are about what the icons draw.
    @BeforeTest
    fun activateIcons() = IconLoader.activate()

    private val font =
        requireNotNull(javaClass.getResourceAsStream("/icon-fonts/icons.woff2")).use { it.readAllBytes() }

    @AfterTest
    fun forget() {
        ContributedIcons.replace(ContributedIconsDto())
    }

    @Test
    fun `visible glyph bounds are centred independently of the font baseline and bearings`() {
        val loaded = assertNotNull(IconFonts.load(font))
        for (character in listOf(0xF101, 'A'.code)) {
            if (!loaded.canDisplay(character)) continue
            val bounds = ContributedIcons.outline(loaded, character).bounds2D
            assertTrue(bounds.width > 0 && bounds.height > 0)
            assertEquals(8.0, bounds.centerX, 0.001)
            assertEquals(8.0, bounds.centerY, 0.001)
        }
    }

    @Test
    fun `a contributed glyph is drawn wherever its name is`() {
        val contributed = ContributedIconsDto(
            listOf(IconFontDto(font, mapOf("test-square" to 0xF101, "test-nothing" to 0xE000))),
            mapOf("test-gear" to "gear", "test-again" to "test-square", "test-loop" to "test-loop"),
        )
        assertTrue(ContributedIcons.replace(contributed))
        assertFalse(ContributedIcons.replace(contributed.copy()), "The same contributions change nothing")

        val square = assertNotNull(ContributedIcons.find("test-square"))
        assertSame(square, Codicons.icon("test-square"))
        assertEquals(16, square.iconWidth)
        assertNull(ContributedIcons.find("test-nothing"), "A character the font does not have")
        // Names of other icons: a codicon, or another contribution.
        assertSame(AllIcons.General.Settings, ContributedIcons.find("test-gear"))
        assertSame(square, ContributedIcons.find("test-again"))
        assertNull(ContributedIcons.find("test-loop"))
    }

    @Test
    fun `a name asked for before its extension's icons are here draws them once they are`() {
        val icon = assertIs<ContributedIcons.Contributed>(Codicons.icon("test-square"))
        assertSame(Codicons.DEFAULT, icon.current)

        ContributedIcons.replace(ContributedIconsDto(listOf(IconFontDto(font, mapOf("test-square" to 0xF101)))))

        assertSame(ContributedIcons.find("test-square"), icon.current)
    }

    @Test
    fun `a selected tool window button draws it in the selection's colour`() {
        // What the new UI's stripe does to a selected button's icon: every icon colour becomes this one.
        val early = Codicons.icon("test-square")
        ContributedIcons.replace(ContributedIconsDto(listOf(IconFontDto(font, mapOf("test-square" to 0xF101)))))

        for (icon in listOf(early, Codicons.icon("test-square"))) {
            assertEquals(setOf(Color.WHITE.rgb), colours(toStrokeIcon(icon, Color.WHITE)), icon.toString())
        }
    }

    /** The opaque colours [icon] paints. */
    private fun colours(icon: Icon): Set<Int> {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        icon.paintIcon(null, graphics, 0, 0)
        graphics.dispose()
        return (0 until image.width).flatMap { x -> (0 until image.height).map { y -> image.getRGB(x, y) } }
            .filter { it ushr 24 == 0xFF }.toSet()
    }
}
