// Draws and opens what GitLens and Error Lens do, with colours no theme uses, so a split-mode test
// can find each one in a screenshot of the client. See README.md.
const vscode = require('vscode');

/** What `probe:` serves for every file: the "revision" a diff compares the file on disk with. */
const REVISION = 'revision text\n';

function activate(context) {
    installUIProbe(context);
    require('./editor-probe').install(context);
    require('./runtime-probe').install(context);
    require('./window-probe').install(context);
    require('./testing-probe').install(context);
    // GitLens serves its revisions as a file system of its own scheme, not a content provider.
    const changes = new vscode.EventEmitter();
    context.subscriptions.push(vscode.workspace.registerFileSystemProvider('probe', {
        onDidChangeFile: changes.event,
        watch: () => new vscode.Disposable(() => {
        }),
        stat: () => ({type: vscode.FileType.File, ctime: 0, mtime: 0, size: REVISION.length}),
        readDirectory: () => [],
        createDirectory: () => {
        },
        readFile: () => new TextEncoder().encode(REVISION),
        writeFile: () => {
        },
        delete: () => {
        },
        rename: () => {
        },
    }, {isReadonly: true}));

    // Error Lens: a whole-line decoration whose message follows the line, in a colour of its own.
    const message = vscode.window.createTextEditorDecorationType({
        isWholeLine: true,
        after: {color: '#ff00ff', margin: '0 0 0 4ch'},
    });
    // GitLens' highlight of a commit's lines: a background, and a mark in the overview ruler per line.
    const highlight = vscode.window.createTextEditorDecorationType({
        isWholeLine: true,
        backgroundColor: '#20206080',
        overviewRulerColor: '#00bcf2',
        overviewRulerLane: vscode.OverviewRulerLane.Center,
    });
    // GitLens' blame column: one width on every line, a background, the heatmap edge on its right
    // and a line above a commit, the last two written as CSS into textDecoration as GitLens does.
    const column = vscode.window.createTextEditorDecorationType({
        before: {
            color: '#000000',
            backgroundColor: '#00ff00',
            borderColor: '#0000ff',
            width: 'calc(12ch - 6px)',
            margin: '0 26px 0 0',
            textDecoration: 'none;text-decoration:overline solid #ffff00;border-style:solid;border-width:0 3px 0 0',
        },
    });

    context.subscriptions.push(message, highlight, column);
    // Passing a different list replaces the previous ranges; an empty list clears them.
    context.subscriptions.push(vscode.commands.registerCommand('probe.decorate', (lineNumbers = [0, 1, 2]) => {
        let decorated = 0;
        for (const editor of vscode.window.visibleTextEditors) {
            if (!editor.document.uri.path.endsWith('/example.json')) continue;
            const lines = lineNumbers.map((line) => new vscode.Range(line, 0, line, 0));
            editor.setDecorations(highlight, lines);
            editor.setDecorations(message, lines.length
                ? [{range: lines[0], renderOptions: {after: {contentText: 'probe message'}}}]
                : []);
            editor.setDecorations(column, lines.map((range, index) => (
                {range, renderOptions: {before: {contentText: index === 0 ? 'blame' : ' '}}}
            )));
            decorated++;
        }
        return decorated;
    }));

    context.subscriptions.push(vscode.commands.registerCommand('probe.disposeDecorations', () => {
        message.dispose();
        highlight.dispose();
        column.dispose();
        return true;
    }));

    context.subscriptions.push(vscode.commands.registerCommand('probe.diff', async () => {
        const folder = vscode.workspace.workspaceFolders[0].uri;
        const file = vscode.Uri.joinPath(folder, 'example.json');
        await vscode.commands.executeCommand('vscode.diff', vscode.Uri.parse('probe:/revisions/example.json'), file, 'Probe diff');
        return true;
    }));
}

/** A tree command opens a real scripted page; all state changes come through VS Code's public API. */
function installUIProbe(context) {
    const changed = new vscode.EventEmitter();
    let generation = 0, panel, clicks = 0;
    // What the panel's page sent besides clicks, for `probe.webviewState`.
    const burst = [], binary = [], links = [];
    const group = {id: 'group'};
    const leaf = {id: 'open'};
    context.subscriptions.push(changed, vscode.window.registerTreeDataProvider('probe.tree', {
        onDidChangeTreeData: changed.event,
        getChildren: item => item ? (item === group ? [leaf] : []) : [group],
        getTreeItem: item => {
            const treeItem = new vscode.TreeItem(item === group ? 'Probe group' : `Open probe panel ${generation}`,
                item === group ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None);
            treeItem.id = item.id;
            if (item === leaf) treeItem.command = {command: 'probe.openPanel', title: 'Open probe panel'};
            return treeItem;
        },
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.refreshTree', () => {
        generation++;
        changed.fire(undefined);
    }));
    const html = (webview, extras = '') => {
        const script = webview.asWebviewUri(vscode.Uri.joinPath(context.extensionUri, 'webview.js'));
        return `<!doctype html><html><head>
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src ${webview.cspSource};">
            </head><body><h1>Probe webview</h1><button id="send">Send to extension</button>
            <p id="count"></p><p id="reply">Waiting for click</p>${extras}<script src="${script}"></script></body></html>`;
    };
    const command = `command:probe.linkFollowed?${encodeURIComponent(JSON.stringify(['from-page']))}`;
    const panelHtml = webview => html(webview, `<p id="hello">No hello</p>
        <button id="burst">Send a burst</button><button id="binary">Send bytes</button>
        <p id="binaryReply">No bytes</p><a id="command" href="${command}">Run a command</a>`);
    context.subscriptions.push(vscode.commands.registerCommand('probe.openPanel', () => {
        if (panel) {
            panel.reveal();
            return;
        }
        panel = vscode.window.createWebviewPanel('probe.page', 'Probe panel', vscode.ViewColumn.One,
            {
                enableScripts: true,
                localResourceRoots: [context.extensionUri],
                enableCommandUris: ['probe.linkFollowed']
            });
        const opened = panel;
        context.subscriptions.push(opened, opened.onDidDispose(() => {
            if (panel === opened) panel = undefined;
        }));
        context.subscriptions.push(opened.webview.onDidReceiveMessage(message => {
            if (message.type === 'burst') burst.push(message.n);
            if (message.type === 'binary') {
                binary.push(message.bytes instanceof Uint8Array ? Array.from(message.bytes) : String(message.bytes));
                opened.webview.postMessage({
                    type: 'binary',
                    bytes: new Uint8Array(Array.from(message.bytes).reverse())
                });
            }
            if (message.type !== 'clicked') return;
            clicks = message.count;
            opened.title = `Probe panel ${clicks}`;
            opened.webview.postMessage({type: 'ack', count: clicks});
        }));
        opened.webview.html = panelHtml(opened.webview);
        // Posted at once, as extensions do: before the page, or even its description, has reached the client.
        opened.webview.postMessage({type: 'hello'});
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.linkFollowed', from => {
        links.push(from);
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.webviewState', () => ({burst, binary, links})));
    context.subscriptions.push(vscode.commands.registerCommand('probe.reloadPage', () => {
        if (!panel) return false;
        panel.webview.html = '';
        panel.webview.html = panelHtml(panel.webview);
        return true;
    }));
    installWebviewViewProbe(context, html);
    context.subscriptions.push(vscode.commands.registerCommand('probe.uiState', () => ({
        open: !!panel,
        clicks,
        generation
    })));
}

/**
 * A webview view: made when its tool window is first shown, answering clicks as the panel does, and
 * keeping what it hears of being hidden and shown.
 */
function installWebviewViewProbe(context, html) {
    let resolved = 0, clicks = 0;
    const visibility = [];
    context.subscriptions.push(vscode.window.registerWebviewViewProvider('probe.webviewView', {
        resolveWebviewView(view) {
            resolved++;
            view.webview.options = {enableScripts: true, localResourceRoots: [context.extensionUri]};
            view.webview.html = html(view.webview);
            view.webview.onDidReceiveMessage(message => {
                if (message.type !== 'clicked') return;
                clicks = message.count;
                view.webview.postMessage({type: 'ack', count: clicks});
            });
            view.onDidChangeVisibility(() => visibility.push(view.visible));
        },
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.viewState', () => ({
        resolved,
        clicks,
        visibility
    })));
}

module.exports = {activate};
