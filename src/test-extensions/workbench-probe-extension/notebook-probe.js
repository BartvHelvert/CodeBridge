const vscode = require('vscode');

/**
 * A kernel for `jupyter-notebook`, the built-in `ipynb` extension's type, as Jupyter's controllers
 * are: it answers each cell with `PROBE RESULT` and the cell's text in capitals, so a test can tell
 * the text the host saw from the text the client shows, or, to a cell saying `wait`, runs until
 * stopped. Every notebook it is told about and every
 * cell it runs are recorded for `probe.notebookState`; `probe.showNotebook` opens one in an editor,
 * and `probe.startShowingNotebook` starts to without waiting for it.
 */
exports.install = context => {
    const state = {opened: [], ran: [], cells: {}};
    const controller = vscode.notebooks.createNotebookController('probe-kernel', 'jupyter-notebook', 'Probe Kernel');
    controller.supportedLanguages = ['python'];
    controller.supportsExecutionOrder = true;
    let order = 0;
    controller.executeHandler = async cells => {
        for (const cell of cells) {
            const execution = controller.createNotebookCellExecution(cell);
            execution.executionOrder = ++order;
            execution.start(Date.now());
            const text = cell.document.getText();
            state.ran.push(text);
            // A cell saying `wait` runs until it is stopped, as a long computation does.
            if (text.includes('wait')) {
                await new Promise(resolve => execution.token.onCancellationRequested(resolve));
                execution.end(false, Date.now());
                continue;
            }
            await execution.replaceOutput([new vscode.NotebookCellOutput([
                vscode.NotebookCellOutputItem.text(`PROBE RESULT ${text.toUpperCase()}`),
            ])]);
            execution.end(true, Date.now());
        }
    };
    const record = notebook => {
        state.cells[notebook.uri.path.split('/').pop()] = notebook.getCells().map(cell => cell.document.getText());
    };
    context.subscriptions.push(
        controller,
        vscode.workspace.onDidOpenNotebookDocument(notebook => {
            state.opened.push(notebook.uri.path.split('/').pop());
            record(notebook);
        }),
        vscode.workspace.onDidChangeNotebookDocument(event => record(event.notebook)),
        vscode.commands.registerCommand('probe.notebookState', () => state),
        // Opens a notebook of the workspace as an extension does: the document, then an editor for it.
        vscode.commands.registerCommand('probe.showNotebook', async (name = 'example.ipynb') => {
            const notebook = await vscode.workspace.openNotebookDocument(vscode.Uri.joinPath(vscode.workspace.workspaceFolders[0].uri, name));
            const editor = await vscode.window.showNotebookDocument(notebook);
            return editor.notebook.cellCount;
        }),
        // The same, answering at once, so a test can watch the IDE while the editor opens.
        vscode.commands.registerCommand('probe.startShowingNotebook', () => {
            vscode.commands.executeCommand('probe.showNotebook');
            return 'started';
        }));
    installCellLanguage(context);
};

/**
 * Language features in those notebooks' cells, claimed by notebook type and language as a language
 * server that knows notebooks claims them: `probeCompletion` to complete, a hover naming the word,
 * a problem at each `oops`, and `defined` defined where the notebook's first cell says it.
 */
function installCellLanguage(context) {
    const cells = {notebookType: 'jupyter-notebook', language: 'python'};
    const problems = vscode.languages.createDiagnosticCollection('probe-cells');
    const check = document => {
        if (document.uri.scheme !== 'vscode-notebook-cell') return;
        const text = document.getText();
        const found = [];
        for (let at = text.indexOf('oops'); at >= 0; at = text.indexOf('oops', at + 1)) {
            const range = new vscode.Range(document.positionAt(at), document.positionAt(at + 4));
            found.push(new vscode.Diagnostic(range, 'PROBE PROBLEM oops', vscode.DiagnosticSeverity.Error));
        }
        problems.set(document.uri, found);
    };
    context.subscriptions.push(
        problems,
        vscode.workspace.onDidOpenTextDocument(check),
        vscode.workspace.onDidChangeTextDocument(event => check(event.document)),
        vscode.languages.registerCompletionItemProvider(cells, {
            provideCompletionItems: () => [
                Object.assign(new vscode.CompletionItem('probeCompletion', vscode.CompletionItemKind.Function), {detail: 'PROBE DETAIL'}),
                // A snippet, whose stop the caret lands on.
                Object.assign(new vscode.CompletionItem('probeCall', vscode.CompletionItemKind.Function), {
                    insertText: new vscode.SnippetString('probeCall(${1:arg})'),
                }),
            ],
        }),
        vscode.languages.registerHoverProvider(cells, {
            provideHover: (document, position) => {
                const range = document.getWordRangeAtPosition(position);
                return range && new vscode.Hover(new vscode.MarkdownString(`PROBE HOVER **${document.getText(range)}**`), range);
            },
        }),
        vscode.languages.registerDefinitionProvider(cells, {
            provideDefinition: (document, position) => {
                const range = document.getWordRangeAtPosition(position);
                if (!range || document.getText(range) !== 'defined') return undefined;
                const notebook = vscode.workspace.notebookDocuments.find(it => it.getCells().some(cell => cell.document === document));
                const first = notebook?.cellAt(0).document;
                return first && new vscode.Location(first.uri, first.positionAt(first.getText().indexOf('defined')));
            },
        }));
    vscode.workspace.textDocuments.forEach(check);
}
