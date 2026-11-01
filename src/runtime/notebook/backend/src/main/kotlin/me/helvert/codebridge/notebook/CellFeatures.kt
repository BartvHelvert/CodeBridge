package me.helvert.codebridge.notebook

import me.helvert.codebridge.exthost.protocol.payload.CompletionItem
import me.helvert.codebridge.exthost.protocol.payload.Completions
import me.helvert.codebridge.exthost.protocol.payload.Marker
import me.helvert.codebridge.language.Snippets

/** What the extension host answers about a cell, as the frontend that draws the cell takes it. */
internal object CellFeatures {

    /**
     * [items] in the order VS Code ranks them, each ready to insert: a snippet as the text it expands
     * to, with the caret on its first stop -- or on its end, with a caret on each further `$0`.
     */
    fun completions(items: List<CompletionItem>): List<CellCompletionDto> = items.sortedWith(Completions.VS_CODE_ORDER).map { item ->
        val expansion = if (item.snippet) Snippets.expand(item.insertText) else null
        val text = expansion?.text ?: item.insertText
        val stop = expansion?.marks?.filter { it.number > 0 }?.minByOrNull { it.number }
        CellCompletionDto(
            label = item.label,
            filter = item.filterText ?: item.label,
            detail = item.detail ?: item.documentation?.lineSequence()?.firstOrNull(),
            kind = item.kind,
            text = text,
            caret = stop?.start ?: expansion?.end ?: text.length,
            carets = if (stop == null) expansion?.ends?.drop(1)?.map { it.end }.orEmpty() else emptyList(),
            replaceLine = item.replace?.startLineNumber,
            replaceColumn = item.replace?.startColumn,
        )
    }

    fun problems(markers: List<Marker>): List<CellProblemDto> = markers.map {
        CellProblemDto(
            it.range.startLineNumber, it.range.startColumn, it.range.endLineNumber, it.range.endColumn,
            it.severity, it.message, it.source,
        )
    }
}
