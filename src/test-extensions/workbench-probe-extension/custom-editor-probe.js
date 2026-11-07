const vscode = require('vscode');

/**
 * The two kinds of custom editor `SplitModeCustomEditorTest` opens in the client, each a page with
 * the document's value and a button that changes it:
 *
 * - `probe.textEditor`, for `.probetext` files, edits the file's text: Append adds `!` with a
 *   workspace edit, and the page follows the text as it changes, whoever changes it.
 * - `probe.counterEditor`, for `.probecounter` files, keeps a document of its own, a number read from
 *   the file: Increment adds one as an edit it can undo and redo, and saving writes the number back.
 *   It claims `.probecounter.pdf` files too, which the IDE would hand to the system's PDF viewer.
 * - `probe.textViewer` is the text editor again, for `.probetext` files too, but opened only when
 *   asked for (`"priority": "option"`), as the Hex Editor is for any file.
 * - `probe.heavyEditor`, for `.probeheavy` files, shows a page that loads every `heavy-*.js` script of
 *   the workspace through `asWebviewUri`, `heavy-big.js` first, as draw.io's page loads its own. The
 *   page reports how long it took to load, which the state lists in `loads`.
 *
 * `probe.customEditorState` reports what each was asked to do; `probe.openCustomEditorFile` opens
 * the workspace's file of any kind as an extension would.
 */
exports.install = context => {
    const state = {
        text: {resolved: [], disposed: 0},
        viewer: {resolved: [], disposed: 0},
        counter: {opened: [], resolved: 0, disposed: 0, saved: [], undone: 0, redone: 0},
        heavy: {loads: []},
    };
    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider('probe.textEditor', textEditor(state.text)),
        vscode.window.registerCustomEditorProvider('probe.counterEditor', counterEditor(state.counter)),
        vscode.window.registerCustomEditorProvider('probe.textViewer', textEditor(state.viewer)),
        vscode.window.registerCustomEditorProvider('probe.heavyEditor', heavyEditor(state.heavy)),
        vscode.commands.registerCommand('probe.customEditorState', () => state),
        // Opens the workspace's custom editor file as an extension opens a file, with `vscode.open`.
        // Opens the workspace's file [file] as an extension opens a file, whichever editor that is.
        vscode.commands.registerCommand('probe.openFile', async file => {
            const [found] = await vscode.workspace.findFiles(file);
            await vscode.commands.executeCommand('vscode.open', found);
            return name(found);
        }),
        vscode.commands.registerCommand('probe.openCustomEditorFile', async () => {
            const [file] = await vscode.workspace.findFiles('*.{probetext,probecounter,probeheavy}');
            await vscode.commands.executeCommand('vscode.open', file);
            return name(file);
        }));
};

const name = uri => uri.path.split('/').pop();

function textEditor(state) {
    return {
        resolveCustomTextEditor(document, panel) {
            state.resolved.push(name(document.uri));
            const render = () => panel.webview.postMessage({value: `Text: ${document.getText().trim()}`});
            const changes = vscode.workspace.onDidChangeTextDocument(event => {
                if (event.document.uri.toString() === document.uri.toString()) render();
            });
            panel.webview.options = {enableScripts: true};
            panel.webview.html = page('Append');
            panel.webview.onDidReceiveMessage(async message => {
                if (message.type === 'ready') render();
                if (message.type === 'act') {
                    const edit = new vscode.WorkspaceEdit();
                    edit.insert(document.uri, document.positionAt(document.getText().trimEnd().length), '!');
                    await vscode.workspace.applyEdit(edit);
                }
            });
            panel.onDidDispose(() => {
                changes.dispose();
                state.disposed++;
            });
        },
    };
}

function counterEditor(state) {
    const changed = new vscode.EventEmitter();
    const pages = new Map();
    const render = document => pages.get(document)?.webview.postMessage({value: `Count: ${document.value}`});
    const read = async uri => Number(new TextDecoder().decode(await vscode.workspace.fs.readFile(uri)).trim()) || 0;
    const write = (uri, value) => vscode.workspace.fs.writeFile(uri, new TextEncoder().encode(`${value}\n`));
    return {
        onDidChangeCustomDocument: changed.event,
        async openCustomDocument(uri) {
            state.opened.push(name(uri));
            const document = {uri, value: await read(uri), dispose: () => state.disposed++};
            return document;
        },
        resolveCustomEditor(document, panel) {
            state.resolved++;
            pages.set(document, panel);
            panel.onDidDispose(() => pages.delete(document));
            panel.webview.options = {enableScripts: true};
            panel.webview.html = page('Increment');
            panel.webview.onDidReceiveMessage(message => {
                if (message.type === 'ready') render(document);
                if (message.type !== 'act') return;
                document.value++;
                render(document);
                changed.fire({
                    document,
                    label: 'Increment',
                    undo: () => {
                        document.value--;
                        state.undone++;
                        render(document);
                    },
                    redo: () => {
                        document.value++;
                        state.redone++;
                        render(document);
                    },
                });
            });
        },
        async saveCustomDocument(document) {
            await write(document.uri, document.value);
            state.saved.push(document.value);
        },
        async saveCustomDocumentAs(document, destination) {
            await write(destination, document.value);
        },
        async revertCustomDocument(document) {
            document.value = await read(document.uri);
            render(document);
        },
        async backupCustomDocument(document, backup) {
            await write(backup.destination, document.value);
            return {id: backup.destination.toString(), delete: () => vscode.workspace.fs.delete(backup.destination)};
        },
    };
}

function heavyEditor(state) {
    return {
        async resolveCustomTextEditor(document, panel) {
            // The large script first, as draw.io's page names its own.
            const scripts = (await vscode.workspace.findFiles('heavy-*.js'))
                .sort((a, b) => name(a) === 'heavy-big.js' ? -1 : name(b) === 'heavy-big.js' ? 1 : 0);
            panel.webview.options = {enableScripts: true};
            panel.webview.onDidReceiveMessage(message => {
                if (message.type === 'loaded') state.loads.push(message);
            });
            const sources = scripts.map(uri => `<script src="${panel.webview.asWebviewUri(uri)}"></script>`).join('\n');
            panel.webview.html = `<!DOCTYPE html>
<html>
<head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src ${panel.webview.cspSource} 'unsafe-inline'"></head>
<body>
<p id="value">Loading</p>
<script>window.__heavy = 0; window.__small = 0;</script>
${sources}
<script>
    const api = acquireVsCodeApi();
    window.addEventListener('load', () => {
        const ms = Math.round(performance.now());
        document.getElementById('value').textContent =
            'Loaded ' + window.__small + ' small scripts and ' + window.__heavy + ' lines in ' + ms + ' ms';
        // Where the time went: when the large script and the small ones were asked for and arrived.
        const resources = performance.getEntriesByType('resource');
        const big = resources.find(entry => entry.name.includes('heavy-big.js'));
        const small = resources.filter(entry => entry.name.includes('heavy-small-'));
        const timing = {
            bigStart: Math.round(big?.startTime ?? -1), bigEnd: Math.round(big?.responseEnd ?? -1),
            smallFirstStart: Math.round(Math.min(...small.map(entry => entry.startTime))),
            smallLastEnd: Math.round(Math.max(...small.map(entry => entry.responseEnd))),
            smallLongest: Math.round(Math.max(...small.map(entry => entry.duration))),
            documentEnd: Math.round(performance.getEntriesByType('navigation')[0]?.responseEnd ?? -1),
            // Whether a service worker answered: it controls the page, and started on its requests.
            controlled: !!navigator.serviceWorker?.controller,
            bigWorkerStart: Math.round(big?.workerStart ?? -1),
        };
        api.postMessage({type: 'loaded', ms, small: window.__small, heavy: window.__heavy, timing});
    });
</script>
</body>
</html>`;
        },
    };
}

/** A page showing the value the extension posts it, with a button sending `act`. */
function page(button) {
    return `<!DOCTYPE html>
<html>
<head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'"></head>
<body>
<p id="value" tabindex="0">Loading</p>
<button id="act">${button}</button>
<script>
    const api = acquireVsCodeApi();
    document.getElementById('act').addEventListener('click', () => api.postMessage({type: 'act'}));
    window.addEventListener('message', event => { document.getElementById('value').textContent = event.data.value; });
    api.postMessage({type: 'ready'});
</script>
</body>
</html>`;
}
