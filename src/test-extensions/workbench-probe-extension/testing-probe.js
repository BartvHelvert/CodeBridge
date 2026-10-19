const vscode = require('vscode');

/**
 * Tests declared with `vscode.tests`, as a language extension declares them: a `suite <name>` line
 * holds the `test <name>` lines after it. Discovered from the open document, again as it changes, so
 * the gutter has to follow edits. A test whose name starts with `fails` fails, and every run's
 * requested tests are recorded, so a test can tell "Rerun Failed Tests" from running everything.
 */
exports.install = context => {
    const state = {runs: 0, requested: []};
    const controller = vscode.tests.createTestController('probeTests', 'Probe tests');
    context.subscriptions.push(controller);
    context.subscriptions.push(vscode.commands.registerCommand('probe.testState', () => state));

    const discover = document => {
        if (document.languageId !== 'codebridgeactions') return;
        const file = controller.createTestItem(document.uri.toString(), document.uri.path.split('/').pop(), document.uri);
        let suite;
        for (let line = 0; line < document.lineCount; line++) {
            const match = /^(suite|test) (\w+)/.exec(document.lineAt(line).text);
            if (!match) continue;
            const item = controller.createTestItem(`${match[1]}:${match[2]}`, match[2], document.uri);
            item.range = new vscode.Range(line, 0, line, match[0].length);
            if (match[1] === 'suite') {
                suite = item;
                file.children.add(item);
            } else (suite ?? file).children.add(item);
        }
        controller.items.replace([file]);
    };
    controller.resolveHandler = async item => {
        if (!item) vscode.workspace.textDocuments.forEach(discover);
    };
    context.subscriptions.push(
        vscode.workspace.onDidOpenTextDocument(discover),
        vscode.workspace.onDidChangeTextDocument(event => discover(event.document)));
    vscode.workspace.textDocuments.forEach(discover);

    const leaves = item => item.children.size ? [...item.children].flatMap(([, child]) => leaves(child)) : [item];
    controller.createRunProfile('Run probe tests', vscode.TestRunProfileKind.Run, async request => {
        const run = controller.createTestRun(request);
        const tests = (request.include ?? [...controller.items].map(([, item]) => item)).flatMap(leaves);
        state.runs++;
        state.requested.push(tests.map(test => test.label));
        for (const test of tests) {
            run.started(test);
            run.appendOutput(`PROBE OUTPUT ${test.label}\r\n`, undefined, test);
            if (test.label.startsWith('fails')) run.failed(test, new vscode.TestMessage(`probe failure in ${test.label}`), 5);
            else run.passed(test, 5);
        }
        run.end();
    }, true);
};
