const vscode = require('vscode');

/**
 * Messages, pickers, input boxes and a status bar item, each answered by the user in the client.
 *
 * A test runs a command and the command returns at once, since the backend waits on its answer only
 * briefly; what the user chose lands in `probe.windowState` when the promise settles, `null` for
 * dismissed, so the test can tell "not answered yet" (absent) from "answered with nothing".
 */
exports.install = context => {
    const answers = {};
    const register = (name, run) => context.subscriptions.push(vscode.commands.registerCommand(name, run));
    const record = (key, promise) => {
        delete answers[key];
        Promise.resolve(promise).then(
            value => {
                answers[key] = value === undefined ? null : value;
            },
            error => {
                answers[key] = `error: ${error.message}`;
            });
        return true;
    };
    register('probe.windowState', () => answers);

    register('probe.message', () => record('message',
        vscode.window.showInformationMessage('Probe balloon question', 'Probe yes', 'Probe no')));
    register('probe.modal', () => record('modal',
        vscode.window.showWarningMessage('Probe modal question', {
            modal: true,
            detail: 'Probe modal detail'
        }, 'Probe accept')));

    const items = [
        {label: 'Probe alpha', description: 'first'},
        {label: 'Probe beta', description: 'second'},
        {label: 'Probe gamma', description: 'third'},
    ];
    register('probe.pick', () => record('pick',
        vscode.window.showQuickPick(items, {title: 'Probe pick'}).then(item => item?.label)));
    register('probe.pickMany', () => record('pickMany',
        vscode.window.showQuickPick(items, {title: 'Probe pick many', canPickMany: true})
            .then(chosen => chosen?.map(item => item.label))));
    register('probe.input', () => record('input', vscode.window.showInputBox({
        title: 'Probe input', prompt: 'Probe name', value: 'seed',
    })));

    // A picker the extension drives: its items follow what is typed, as GitLens' searches do.
    register('probe.livePick', () => {
        const pick = vscode.window.createQuickPick();
        pick.title = 'Probe live pick';
        pick.items = [{label: 'Probe live start'}];
        const values = [];
        pick.onDidChangeValue(value => {
            values.push(value);
            pick.items = value ? [{label: `Probe search ${value}`, alwaysShow: true}] : [{label: 'Probe live start'}];
        });
        const done = new Promise(resolve => {
            pick.onDidAccept(() => {
                resolve({accepted: pick.selectedItems.map(item => item.label), value: pick.value});
                pick.hide();
            });
            pick.onDidHide(() => {
                resolve(undefined);
                pick.dispose();
            });
        });
        pick.show();
        answers.liveValues = values;
        return record('livePick', done);
    });

    // A clickable item whose command changes its own text, as a language server's status does.
    let clicks = 0;
    const item = vscode.window.createStatusBarItem('probe.status', vscode.StatusBarAlignment.Left, 100);
    item.text = '$(beaker) Probe status 0';
    item.tooltip = 'Probe status tooltip';
    item.command = 'probe.statusClicked';
    item.show();
    context.subscriptions.push(item);
    register('probe.statusClicked', () => {
        clicks++;
        item.text = `$(beaker) Probe status ${clicks}`;
        answers.statusClicks = clicks;
    });
    register('probe.hideStatus', () => {
        item.hide();
        return true;
    });
};
