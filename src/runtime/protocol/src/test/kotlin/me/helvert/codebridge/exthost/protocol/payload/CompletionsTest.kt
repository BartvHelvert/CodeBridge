package me.helvert.codebridge.exthost.protocol.payload

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading `$provideCompletionItems`' reply, which arrives under single-letter keys.
 *
 * `{x: cacheId, a: defaultRanges, b: items}`, each item `{a: label, b: kind, c: detail, h:
 * insertText, i: insertTextRules, j: range}`. None of that is published anywhere; it was read off
 * the runtime bundle, which means it is exactly the kind of thing that is wrong once and then
 * silently wrong forever. These pin it against the shapes the bundle actually builds.
 */
class CompletionsTest {

    @Test
    fun `reads a plain item`() {
        val item = Completions.parse(
            reply("""{"a":"println","b":1,"c":"fun println(message: Any?)","h":"println"}"""),
        ).items.single()
        assertEquals("println", item.label)
        assertEquals("println", item.insertText)
        assertEquals(1, item.kind)
        assertEquals("fun println(message: Any?)", item.detail)
        assertTrue(!item.snippet)
    }

    @Test
    fun `falls back to the label when there is no insert text`() {
        // Most servers omit it, because for most items it is the label.
        val item = Completions.parse(reply("""{"a":"value"}""")).items.single()
        assertEquals("value", item.insertText)
    }

    @Test
    fun `reads a split label`() {
        // CompletionItemLabel: the name, with its signature and origin alongside it.
        val item = Completions.parse(
            reply("""{"a":{"label":"map","detail":"(transform)","description":"kotlin.collections"}}"""),
        ).items.single()
        assertEquals("map", item.label)
    }

    @Test
    fun `recognises a snippet by its insert text rule`() {
        // Bit 4 is InsertAsSnippet; bit 1 is KeepWhitespace and changes nothing we draw.
        val items = Completions.parse(
            reply($$"""{"a":"if","h":"if (${1:cond})","i":4}""", """{"a":"x","h":"x","i":1}"""),
        ).items
        assertTrue(items[0].snippet)
        assertTrue(!items[1].snippet, "KeepWhitespace is not a snippet")
    }

    @Test
    fun `takes the insert half of a default range`() {
        // `insert` runs from the word start to the caret, which is the prefix IntelliJ matches on.
        // `replace` would additionally swallow the rest of the word, which has no equivalent here.
        val item = Completions.parse(reply("""{"a":"value"}""")).items.single()
        val span = requireNotNull(item.replace)
        assertEquals(3, span.startLineNumber)
        assertEquals(5, span.startColumn)
        assertEquals(8, span.endColumn)
    }

    @Test
    fun `an item's own range wins over the default`() {
        val item = Completions.parse(
            reply("""{"a":"value","j":{"startLineNumber":9,"startColumn":1,"endLineNumber":9,"endColumn":4}}"""),
        ).items.single()
        assertEquals(9, requireNotNull(item.replace).startLineNumber)
    }

    @Test
    fun `reads documentation whether it is a string or a markdown string`() {
        val items = Completions.parse(
            reply("""{"a":"one","d":"plain"}""", """{"a":"two","d":{"value":"**bold**"}}"""),
        ).items
        assertEquals("plain", items[0].documentation)
        assertEquals("**bold**", items[1].documentation)
    }

    @Test
    fun `returns the cache the host is holding, so it can be handed back`() {
        // Left unreleased, the host keeps every item of every lookup the user ever opened.
        assertEquals(7, Completions.parse(reply("""{"a":"x"}""")).cacheId)
    }

    @Test
    fun `a command the host keeps is run through the host's own command, given its handle`() {
        // CodeLLDB's "CodeLLDB: Cargo ...": its arguments include the document, which cannot be sent.
        val item =
            Completions.parse(reply("""{"a":"CodeLLDB: Cargo ...","h":"","i":4,"n":"3","o":"__vsc1a2b"}""")).items.single()

        assertEquals("__vsc1a2b", item.command?.id)
        assertEquals(listOf("3"), item.command?.arguments?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a command with plain arguments carries them`() {
        val item =
            Completions.parse(reply("""{"a":"x","o":"editor.action.triggerSuggest","p":[1,"two"]}""")).items.single()

        assertEquals("editor.action.triggerSuggest", item.command?.id)
        assertEquals(listOf("1", "two"), item.command?.arguments?.map { it.jsonPrimitive.content })
        assertNull(Completions.parse(reply("""{"a":"x"}""")).items.single().command)
    }

    @Test
    fun `an empty or unreadable reply is no completions rather than a failure`() {
        assertTrue(Completions.parse("").items.isEmpty())
        assertTrue(Completions.parse("null").items.isEmpty())
        assertTrue(Completions.parse("""{"x":1,"b":[]}""").items.isEmpty())
        assertNull(Completions.parse("not json").cacheId)
    }

    @Test
    fun `an item keeps its place in the host's list when an unreadable one before it is dropped`() {
        val items = Completions.parse(reply("""{"b":1}""", """{"a":"second"}""")).items
        assertEquals("second", items.single().label)
        assertEquals(1, items.single().index)
    }

    @Test
    fun `tells markdown documentation from plain text`() {
        val (plain, markdown) = Completions.parse(
            reply("""{"a":"p","d":"a *b*"}""", """{"a":"m","d":{"value":"a *b*"}}"""),
        ).items
        assertTrue(!plain.documentationIsMarkdown)
        assertTrue(markdown.documentationIsMarkdown)
        assertEquals("a *b*", markdown.documentation)
    }

    @Test
    fun `names an item to resolve by its provider and its chained cache id`() {
        // `$resolveCompletionItem(handle, ChainedCacheId)`, the id being `[cacheId, index]`.
        assertEquals("[3,[7,12]]", Completions.resolve(Resolvable(3, 7, 12)))
    }

    @Test
    fun `reads a resolved item`() {
        // The host answers with the whole item again, its detail and documentation filled in.
        val resolved = Completions.parseResolved(
            """{"a":"push","c":"pub fn push(&mut self, value: T)","d":{"value":"Appends an element."},"h":"push"}""",
        )
        assertEquals("pub fn push(&mut self, value: T)", resolved?.detail)
        assertEquals("Appends an element.", resolved?.documentation)
        assertEquals(true, resolved?.documentationIsMarkdown)
    }

    @Test
    fun `a provider with nothing to resolve answers nothing`() {
        // `undefined` from the host, which reaches the workbench as no reply at all.
        assertNull(Completions.parseResolved(""))
        assertNull(Completions.parseResolved("null"))
    }

    /** `{x: cacheId, a: defaultRanges, b: items}`, as the host builds it. */
    private fun reply(vararg items: String) =
        """{"x":7,"a":{"insert":{"startLineNumber":3,"startColumn":5,"endLineNumber":3,"endColumn":8},""" +
                """"replace":{"startLineNumber":3,"startColumn":5,"endLineNumber":3,"endColumn":12}},""" +
                """"b":[${items.joinToString(",")}]}"""
}
