"""
Builds the icon fonts IconFontsTest reads: one small font of our own, drawn here, as TrueType, WOFF
and WOFF2 -- the WOFF2 once with only `glyf`/`loca` transformed, as fontTools and most tools write
it, and once with `hmtx` transformed too.

    pip install fonttools brotli
    python generate.py
"""
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import GlyphComponent
from fontTools.ttLib import woff2


def square(pen):
    # Deltas past 255 and of both signs, for the long point encodings.
    pen.moveTo((100, 0))
    pen.lineTo((900, 0))
    pen.lineTo((900, 800))
    pen.lineTo((100, 800))
    pen.closePath()


def ring(pen):
    # Two contours, curved: off-curve points and small deltas.
    pen.moveTo((500, 50))
    pen.qCurveTo((850, 50), (850, 400))
    pen.qCurveTo((850, 750), (500, 750))
    pen.qCurveTo((150, 750), (150, 400))
    pen.qCurveTo((150, 50), (500, 50))
    pen.closePath()
    pen.moveTo((500, 200))
    pen.qCurveTo((300, 200), (300, 400))
    pen.qCurveTo((300, 600), (500, 600))
    pen.qCurveTo((700, 600), (700, 400))
    pen.qCurveTo((700, 200), (500, 200))
    pen.closePath()


def dot(pen):
    pen.moveTo((450, 350))
    pen.lineTo((550, 350))
    pen.lineTo((550, 450))
    pen.lineTo((451, 451))
    pen.closePath()


def draw(shape):
    pen = TTGlyphPen(None)
    if shape:
        shape(pen)
    return pen.glyph()


order = [".notdef", "square", "ring", "dot", "pair"]
builder = FontBuilder(1000, isTTF=True)
builder.setupGlyphOrder(order)
builder.setupCharacterMap({0xF101: "square", 0xF102: "ring", 0xF103: "dot", 0xF104: "pair"})
glyphs = {".notdef": draw(None), "square": draw(square), "ring": draw(ring), "dot": draw(dot)}
# A composite: the dot, twice, once moved and once scaled.
pair = draw(None)
pair.numberOfContours = -1
first = GlyphComponent()
first.glyphName, first.x, first.y, first.flags = "dot", -300, 0, 0
second = GlyphComponent()
second.glyphName, second.x, second.y, second.flags = "dot", 300, 0, 0
second.transform = [[0.5, 0], [0, 0.5]]
pair.components = [first, second]
glyphs["pair"] = pair
builder.setupGlyf(glyphs)
glyf = builder.font["glyf"]
for name in order:
    glyf[name].recalcBounds(glyf)
metrics = {name: (1000, getattr(glyf[name], "xMin", 0)) for name in order}
builder.setupHorizontalMetrics(metrics)
builder.setupHorizontalHeader(ascent=850, descent=-150)
builder.setupNameTable({"familyName": "CodeBridge Test Icons", "styleName": "Regular",
                       "fullName": "CodeBridge Test Icons", "psName": "CodeBridgeTestIcons"})
builder.setupOS2(sTypoAscender=850, sTypoDescender=-150, usWinAscent=850, usWinDescent=150)
builder.setupPost()
builder.save("icons.ttf")

font = TTFont("icons.ttf")
font.flavor = "woff"
font.save("icons.woff")
font = TTFont("icons.ttf")
font.flavor = "woff2"
font.save("icons.woff2")
woff2.compress("icons.ttf", "icons-hmtx.woff2", transform_tables={"glyf", "loca", "hmtx"})
