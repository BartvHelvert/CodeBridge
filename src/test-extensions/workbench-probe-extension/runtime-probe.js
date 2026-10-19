const vscode = require('vscode');

exports.install = context => {
    const state = {taskOpened: 0, taskClosed: 0, debugStarted: 0, debugStopped: 0, steps: 0, pauses: 0, continues: 0};
    context.subscriptions.push(vscode.commands.registerCommand('probe.runtimeState', () => state));
    context.subscriptions.push(vscode.tasks.onDidStartTaskProcess(event => {
        if (event.execution.task.name === 'Probe UI task') state.taskOpened++;
    }), vscode.tasks.onDidEndTask(event => {
        if (event.execution.task.name === 'Probe UI task') state.taskClosed++;
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.startTask', async () => {
        const execution = new vscode.ProcessExecution(process.execPath,
            ['-e', "console.log('PROBE TASK READY'); setInterval(() => {}, 1000)"],
            {env: {ELECTRON_RUN_AS_NODE: '1'}});
        await vscode.tasks.executeTask(new vscode.Task({type: 'probe-ui'}, vscode.TaskScope.Workspace,
            'Probe UI task', 'probe', execution));
        return true;
    }));
    context.subscriptions.push(vscode.debug.registerDebugAdapterDescriptorFactory('codebridge-ui-probe', {
        createDebugAdapterDescriptor: session => new vscode.DebugAdapterInlineImplementation(new Adapter(session.configuration.program, state)),
    }));
    context.subscriptions.push(vscode.commands.registerCommand('probe.startDebug', async () =>
        vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0], {
            name: 'Probe UI debug', type: 'codebridge-ui-probe', request: 'launch',
            program: vscode.Uri.joinPath(vscode.workspace.workspaceFolders[0].uri, 'example.codebridgeactions').fsPath,
        })));
};

/** A deterministic DAP peer: stop at entry, step one line, resume, pause, and disconnect. */
class Adapter {
    constructor(program, state) {
        this.program = program;
        this.state = state;
        this.sequence = 1;
        this.line = 1;
        this.events = new vscode.EventEmitter();
        this.onDidSendMessage = this.events.event;
    }

    send(message) {
        this.events.fire({seq: this.sequence++, ...message});
    }

    event(event, body = {}) {
        this.send({type: 'event', event, body});
    }

    stopped(reason) {
        this.event('stopped', {reason, threadId: 1, allThreadsStopped: true});
    }

    handleMessage(request) {
        const reply = body => this.send({
            type: 'response',
            request_seq: request.seq,
            command: request.command,
            success: true,
            body
        });
        switch (request.command) {
            case 'initialize':
                reply({supportsConfigurationDoneRequest: true, supportsTerminateRequest: true});
                this.event('initialized');
                break;
            case 'launch':
                this.state.debugStarted++;
                reply({});
                this.event('output', {category: 'console', output: 'PROBE DEBUG READY\n'});
                break;
            case 'configurationDone':
                reply({});
                this.stopped('entry');
                break;
            case 'threads':
                reply({threads: [{id: 1, name: 'Probe thread'}]});
                break;
            case 'stackTrace':
                reply({
                    stackFrames: [{
                        id: 1, name: `probe frame ${this.line}`,
                        source: {name: 'example.codebridgeactions', path: this.program}, line: this.line, column: 1
                    }], totalFrames: 1
                });
                break;
            case 'scopes':
                reply({scopes: [{name: 'Locals', variablesReference: 1, expensive: false}]});
                break;
            case 'variables':
                reply({
                    variables: [{
                        name: 'probeValue',
                        value: String(40 + this.line),
                        type: 'number',
                        variablesReference: 0
                    }]
                });
                break;
            case 'next':
                this.line = 2;
                this.state.steps++;
                reply({});
                this.stopped('step');
                break;
            case 'continue':
                this.state.continues++;
                reply({allThreadsContinued: true});
                this.event('continued', {threadId: 1, allThreadsContinued: true});
                break;
            case 'pause':
                this.state.pauses++;
                reply({});
                this.stopped('pause');
                break;
            case 'setBreakpoints':
                reply({breakpoints: []});
                break;
            case 'disconnect':
            case 'terminate':
                // Once per session: a client that stops one sends `terminate` and then `disconnect`.
                if (!this.ended) this.state.debugStopped++;
                this.ended = true;
                reply({});
                this.event('terminated');
                break;
            default:
                reply({});
        }
    }

    dispose() {
        this.events.dispose();
    }
}
