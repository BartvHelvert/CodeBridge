// Shaped like a published extension: one bundled entry point, no node_modules beside it, and
// activation on a language rather than '*'. The server it starts is an ordinary child process on
// stdio, which is the only thing the proxy path needs to see.
const path = require('node:path');
const { LanguageClient, TransportKind } = require('vscode-languageclient/node');

let client;

exports.activate = async context => {
  client = new LanguageClient('codebridge-bundled-probe', 'LSP bundled probe', {
    command: process.execPath,
    // __dirname is dist/ once bundled; the server stays a plain file, since bundling a dependency
    // -free script would not change what the proxy sees.
    args: [path.join(__dirname, '..', 'server.cjs')],
    transport: TransportKind.stdio,
    options: { env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' } },
  }, { documentSelector: [{ language: 'codebridgebundled' }] });
  context.subscriptions.push(client);
  await client.start();
  const result = await client.sendRequest('probe/host', { value: 'extension' });
  console.log('LSP_HOST_RESULT:' + result);
};

exports.deactivate = () => client?.stop();
