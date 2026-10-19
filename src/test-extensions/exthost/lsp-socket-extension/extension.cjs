// A server the proxy path cannot reach.
//
// `TransportKind.socket` makes the client listen on an ephemeral port, spawn the server with
// `--socket=<port>`, and wait for it to connect back. The child process still exists -- the preload
// sees it spawn -- but its stdin never carries an LSP `initialize`, so there is nothing to
// intercept. That is the boundary this fixture pins.
const net = require('node:net');
const path = require('node:path');
const {LanguageClient, TransportKind} = require('vscode-languageclient/node');

let client;

/** The library needs a concrete port: it listens there and passes `--socket=<port>` to the child. */
const freePort = () => new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.on('error', reject);
    probe.listen(0, '127.0.0.1', () => {
        const {port} = probe.address();
        probe.close(() => resolve(port));
    });
});

exports.activate = async context => {
    const port = await freePort();
    client = new LanguageClient('codebridge-socket-probe', 'LSP socket probe', {
        command: process.execPath,
        args: [path.join(__dirname, 'server.cjs')],
        transport: {kind: TransportKind.socket, port},
        options: {env: {...process.env, ELECTRON_RUN_AS_NODE: '1'}},
    }, {documentSelector: [{language: 'codebridgesocket'}]});
    context.subscriptions.push(client);
    await client.start();
    const result = await client.sendRequest('probe/host', {value: 'extension'});
    console.log('LSP_HOST_RESULT:' + result);
};

exports.deactivate = () => client?.stop();
