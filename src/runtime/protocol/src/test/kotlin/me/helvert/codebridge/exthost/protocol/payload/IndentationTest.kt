package me.helvert.codebridge.exthost.protocol.payload

import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("KotlinMisorderedAssertEqualsArguments") // expected values do come first
class IndentationTest {

    @Test
    fun `a launch json indented by two is guessed as two, whatever the default`() {
        val launch = $$"""
            {
              "version": "0.2.0",
              "configurations": [
                {
                  // Listed as "Debug greeter (greeter)".
                  "name": "Debug greeter",
                  "args": ["${input:who}"],
                  "cwd": "${workspaceFolder}"
                }
              ],
              "inputs": [
                { "id": "who", "type": "promptString" }
              ]
            }
        """.trimIndent()
        assertEquals(Indentation(2, true), Indentation.guess(launch, Indentation(4, true)))
        assertEquals(Indentation(2, true), Indentation.guess(launch.replace("\n", "\r\n"), Indentation(8, false)))
    }

    @Test
    fun `four spaces and tabs are recognised`() {
        assertEquals(
            Indentation(4, true),
            Indentation.guess("fn main() {\n    if x {\n        y();\n    }\n}", Indentation(2, true))
        )
        assertEquals(
            Indentation(4, false),
            Indentation.guess("fn main() {\n\tif x {\n\t\ty();\n\t}\n}", Indentation(4, true))
        )
    }

    @Test
    fun `text with no indentation keeps the default`() {
        assertEquals(Indentation(3, false), Indentation.guess("a\nb\n\nc", Indentation(3, false)))
        assertEquals(Indentation.DEFAULT, Indentation.guess(""))
    }

    @Test
    fun `mostly four with a few lines at two is two, as VS Code decides`() {
        // Steps of 4, 4, 4, 2 and 2: four is the commonest, and two is still half as common.
        val text = "a\n    b\n        c\n    d\n      e\n    f"
        assertEquals(Indentation(2, true), Indentation.guess(text))
    }
}
