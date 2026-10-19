const vscode = require('vscode');

function activate(context) {
    console.log('PROBE: activate() called');
    console.log('PROBE: vscode.version = ' + vscode.version);
    console.log('PROBE: extensionPath = ' + context.extensionPath);
    console.log('PROBE: api keys = ' + Object.keys(vscode).slice(0, 12).join(','));
    vscode.window.showInformationMessage('Hello from inside the real extension host');
    const channel = vscode.window.createOutputChannel('Probe');
    channel.appendLine('probe output channel line');
    return {probe: true};
}

function deactivate() {
}

module.exports = {activate, deactivate};
