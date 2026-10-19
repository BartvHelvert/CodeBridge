'use strict';
// Controlled server for the IntelliJ prototype. Its response depends on the document received
// through the extension's real LanguageClient, making stale/missing synchronization visible.
function createServer(send, identity = 'Shared server PID ' + process.pid) {
    const documents = new Map();
    let initialized = false, opens = 0, changes = 0, saves = 0, closes = 0, completions = 0, definitions = 0;
    const canonical = uri => {
        const value = new URL(uri);
        return decodeURIComponent(value.pathname).replace(/^\/[A-Z]:/, m => m.toLowerCase());
    };
    const publish = (uri, document) => send({
        method: 'textDocument/publishDiagnostics', params: {
            uri, version: document.version, diagnostics: document.text.split('\n').flatMap((line, lineNumber) => {
                const character = line.indexOf('ERROR');
                return character < 0 ? [] : [{
                    range: {start: {line: lineNumber, character}, end: {line: lineNumber, character: character + 5}},
                    severity: 1, source: 'CodeBridge prototype', message: 'Replace ERROR to clear this diagnostic'
                }];
            })
        }
    });
    return message => {
        const {method, params = {}, id} = message;
        const reply = result => send({id, result});
        const failure = (code, text) => send({id, error: {code, message: text}});
        switch (method) {
            case 'initialize':
                if (initialized) return failure(-32600, 'Server initialized twice');
                initialized = true;
                return reply({
                    capabilities: {
                        hoverProvider: true, definitionProvider: true, completionProvider: {triggerCharacters: ['.']},
                        textDocumentSync: {openClose: true, change: 1, save: true}
                    }
                });
            case 'initialized':
                // Force the real LanguageClient to create its output channel, even on a healthy startup.
                return send({
                    method: 'window/logMessage',
                    params: {type: 3, message: 'CodeBridge bundled probe ready: ' + identity}
                });
            case 'textDocument/didOpen': {
                opens++;
                const item = params.textDocument;
                documents.set(canonical(item.uri), {text: item.text, version: item.version});
                return publish(item.uri, documents.get(canonical(item.uri)));
            }
            case 'textDocument/didChange': {
                changes++;
                const document = documents.get(canonical(params.textDocument.uri));
                if (!document) throw Error('Change before open');
                if (params.contentChanges.some(change => change.range)) throw Error('Expected LanguageClient full document synchronization');
                document.text = params.contentChanges.at(-1).text;
                document.version = params.textDocument.version;
                return publish(params.textDocument.uri, document);
            }
            case 'textDocument/didSave':
                saves++;
                return;
            case 'textDocument/didClose':
                closes++;
                documents.delete(canonical(params.textDocument.uri));
                return send({
                    method: 'textDocument/publishDiagnostics',
                    params: {uri: params.textDocument.uri, diagnostics: []}
                });
            case 'textDocument/completion': {
                // Answers from the synchronized document, like the hover does, so a completion computed
                // against a stale buffer is a wrong label rather than a silence.
                const document = documents.get(canonical(params.textDocument.uri));
                if (!document) return failure(-32002, 'Document was not synchronized');
                completions++;
                const line = document.text.split('\n')[params.position.line] || '';
                return reply([
                    {
                        label: `version-${document.version}`,
                        kind: 1,
                        detail: line,
                        insertText: `version-${document.version}`
                    },
                    // insertTextFormat 2 is Snippet; the client turns it into a SnippetString, which reaches
                    // the workbench as the InsertAsSnippet rule rather than as literal text.
                    {label: 'wrap', kind: 15, insertText: 'wrap(${1:value})', insertTextFormat: 2},
                ]);
            }
            case 'textDocument/definition': {
                // A LocationLink rather than a plain Location, so the separate selection range travels the
                // whole route; the client only sends links because it declared linkSupport. The column it
                // points at is the document version the server holds, which makes a buffer that never
                // reached it a wrong caret rather than a silence.
                const document = documents.get(canonical(params.textDocument.uri));
                if (!document) return failure(-32002, 'Document was not synchronized');
                definitions++;
                return reply([{
                    targetUri: params.textDocument.uri,
                    targetRange: {start: {line: 0, character: 0}, end: {line: 0, character: 5}},
                    targetSelectionRange: {
                        start: {line: 0, character: document.version},
                        end: {line: 0, character: document.version + 1}
                    },
                    originSelectionRange: {start: params.position, end: params.position},
                }]);
            }
            case 'textDocument/hover': {
                const document = documents.get(canonical(params.textDocument.uri));
                if (!document) return failure(-32002, 'Document was not synchronized');
                const line = document.text.split('\n')[params.position.line] || '';
                return reply({
                    contents: {
                        kind: 'plaintext',
                        value: `${identity}; version ${document.version}: ${line}`
                    }
                });
            }
            case 'probe/host':
                return reply(identity);
            case 'probe/state':
                return reply({
                    identity,
                    initializes: initialized ? 1 : 0,
                    opens,
                    changes,
                    saves,
                    closes,
                    completions,
                    definitions,
                    documents: documents.size
                });
            case 'shutdown':
                return reply(null);
            case 'exit':
                return;
            default:
                if (id !== undefined) failure(-32601, 'Unknown method: ' + method);
        }
    };
}

exports.createServer = createServer;
if (require.main === module) {
    let buffer = Buffer.alloc(0);
    const receive = createServer(message => {
        const body = Buffer.from(JSON.stringify({jsonrpc: '2.0', ...message}));
        process.stdout.write(`Content-Length: ${body.length}\r\n\r\n`);
        process.stdout.write(body);
    });
    process.stdin.on('data', chunk => {
        buffer = Buffer.concat([buffer, chunk]);
        for (; ;) {
            const end = buffer.indexOf('\r\n\r\n');
            if (end < 0) return;
            const length = Number(/Content-Length:\s*(\d+)/i.exec(buffer.subarray(0, end).toString())[1]);
            if (buffer.length < end + 4 + length) return;
            const message = JSON.parse(buffer.subarray(end + 4, end + 4 + length));
            buffer = buffer.subarray(end + 4 + length);
            receive(message);
            if (message.method === 'exit') process.exit(0);
        }
    });
    process.stdin.on('end', () => process.exit(0));
}
