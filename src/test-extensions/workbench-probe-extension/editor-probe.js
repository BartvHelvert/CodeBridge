const vscode = require('vscode');

exports.install = context => {
    const selector = {language: 'codebridgeactions'};
    const fullRange = document => new vscode.Range(document.positionAt(0), document.positionAt(document.getText().length));
    context.subscriptions.push(vscode.languages.registerDocumentFormattingEditProvider(selector, {
        provideDocumentFormattingEdits: document => [vscode.TextEdit.replace(fullRange(document),
            document.getText().replace(/\s*=\s*/g, ' = '))],
    }));
    context.subscriptions.push(vscode.languages.registerRenameProvider(selector, {
        prepareRename: (document, position) => document.getWordRangeAtPosition(position),
        provideRenameEdits: (document, position, newName) => {
            const word = document.getText(document.getWordRangeAtPosition(position));
            const edit = new vscode.WorkspaceEdit();
            for (const match of document.getText().matchAll(/\b[A-Za-z_]\w*\b/g)) {
                if (match[0] === word) edit.replace(document.uri,
                    new vscode.Range(document.positionAt(match.index), document.positionAt(match.index + word.length)), newName);
            }
            return edit;
        },
    }));
    // Extend Selection: the word, then a `name=value` assignment around it, then its line -- the
    // middle step is no range of the IDE's own.
    context.subscriptions.push(vscode.languages.registerSelectionRangeProvider(selector, {
        provideSelectionRanges: (document, positions) => positions.map(position => {
            const line = document.lineAt(position.line);
            let range = new vscode.SelectionRange(line.range);
            for (const match of line.text.matchAll(/\w+=\w+/g)) {
                const assignment = new vscode.Range(position.line, match.index, position.line, match.index + match[0].length);
                if (assignment.contains(position)) range = new vscode.SelectionRange(assignment, range);
            }
            const word = document.getWordRangeAtPosition(position);
            return word ? new vscode.SelectionRange(word, range) : range;
        }),
    }));
    // `#L<n>` after the file's own name links to that line of it.
    context.subscriptions.push(vscode.languages.registerDocumentLinkProvider(selector, {
        provideDocumentLinks: document => [...document.getText().matchAll(/example\.codebridgeactions#L(\d+)/g)].map(match =>
            new vscode.DocumentLink(
                new vscode.Range(document.positionAt(match.index), document.positionAt(match.index + match[0].length)),
                document.uri.with({fragment: `L${match[1]}`}))),
    }));
    // `greet(` before the caret is finished as `greet("world")`, over the rest of the line -- the
    // `)` an editor may have closed -- and accepting it runs a command, as a completion engine
    // records what was taken.
    const inline = {asked: [], accepted: []};
    context.subscriptions.push(vscode.languages.registerInlineCompletionItemProvider(selector, {
        provideInlineCompletionItems: (document, position, inlineContext) => {
            inline.asked.push(inlineContext.triggerKind);
            const line = document.lineAt(position.line);
            if (line.text.substring(0, position.character) !== 'greet(') return [];
            const item = new vscode.InlineCompletionItem('greet("world")', line.range);
            item.command = {title: 'Accepted', command: 'probe.inlineAccepted', arguments: ['world']};
            return [item];
        },
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.inlineAccepted', name => inline.accepted.push(name)));
    context.subscriptions.push(vscode.commands.registerCommand('probe.inlineState', () => inline));
    // `;` typed at the end of a line marks it, as an on-type formatter adds what typing implies.
    context.subscriptions.push(vscode.languages.registerOnTypeFormattingEditProvider(selector, {
        provideOnTypeFormattingEdits: (document, position) => {
            const line = document.lineAt(position.line);
            return line.text.endsWith(';') && !line.text.includes('//') ? [vscode.TextEdit.insert(line.range.end, ' // typed')] : [];
        },
    }, ';'));
    // `<name>...</name>`: the two names are edited together.
    context.subscriptions.push(vscode.languages.registerLinkedEditingRangeProvider(selector, {
        provideLinkedEditingRanges: (document, position) => {
            const text = document.lineAt(position.line).text;
            const match = /<(\w+)>[^<]*<\/(\w+)>/.exec(text);
            if (!match) return undefined;
            const open = new vscode.Range(position.line, match.index + 1, position.line, match.index + 1 + match[1].length);
            const closeStart = match.index + match[0].length - 1 - match[2].length;
            const close = new vscode.Range(position.line, closeStart, position.line, closeStart + match[2].length);
            return open.contains(position) || close.contains(position)
                ? new vscode.LinkedEditingRanges([open, close], /[a-z]+/) : undefined;
        },
    }));
    // `#rrggbb`, written back the same way.
    const hex = value => Math.round(value * 255).toString(16).padStart(2, '0');
    context.subscriptions.push(vscode.languages.registerColorProvider(selector, {
        provideDocumentColors: document => [...document.getText().matchAll(/#([0-9a-f]{6})\b/gi)].map(match => {
            const [r, g, b] = [0, 2, 4].map(i => parseInt(match[1].substring(i, i + 2), 16) / 255);
            return new vscode.ColorInformation(
                new vscode.Range(document.positionAt(match.index), document.positionAt(match.index + 7)), new vscode.Color(r, g, b, 1));
        }),
        provideColorPresentations: color => [new vscode.ColorPresentation(`#${hex(color.red)}${hex(color.green)}${hex(color.blue)}`)],
    }));
    context.subscriptions.push(vscode.languages.registerCodeActionsProvider(selector, {
        provideCodeActions: document => {
            const offset = document.getText().indexOf('ERROR');
            if (offset < 0) return [];
            const action = new vscode.CodeAction('Probe: replace ERROR with OK', vscode.CodeActionKind.QuickFix);
            action.edit = new vscode.WorkspaceEdit();
            action.edit.replace(document.uri, new vscode.Range(document.positionAt(offset), document.positionAt(offset + 5)), 'OK');
            return [action];
        },
    }));
};
