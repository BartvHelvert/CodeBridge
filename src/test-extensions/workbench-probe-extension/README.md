# Workbench probe

What `SplitModeWorkbenchTest` asks a real extension host to do, as GitLens and Error Lens do it,
in colours no theme uses so each can be found in a screenshot of the client:

- `probe.decorate` draws, on `example.json`, an Error Lens message (`#ff00ff`) after a line that
  also has a GitLens-style whole-line highlight with an overview ruler mark (`#00bcf2`) on three
  lines, and a GitLens-style blame column: a fixed width, a green (`#00ff00`) background, a blue (`#0000ff`) heatmap
  edge and a yellow (`#ffff00`) line above it. It answers how many editors it
  decorated.
- `probe.diff` runs `vscode.diff` from `probe:/revisions/example.json` to the file on disk.
  `probe:` is a file system provider, as `gitlens:` is, serving `revision text` for every file.

`window-probe.js` is what `SplitModeWindowTest` answers in the client: a status bar item whose
command changes its text, a message balloon and a modal message, single and multiple quick picks,
an input box and a `createQuickPick` whose items follow what is typed. Each command returns at once;
the answer appears in `probe.windowState` when the extension's promise settles.

`testing-probe.js` is what `SplitModeTestingTest` runs from the client's gutter: a `TestController`
reading `suite <name>` and `test <name>` lines from open `.codebridgeactions` documents, rediscovered as
they change, whose Run profile passes every test but those named `fails...`. `probe.testState`
lists the tests each run asked for.

`editor-probe.js` gives `.codebridgeactions` files the language features `SplitModeActionsTest` and
`SplitModeLanguageFeaturesTest` use from the client: a formatter, rename, a quick fix for `ERROR`,
selection ranges that step from a word through a `name=value` assignment to its line, a link from
`example.codebridgeactions#L<n>` to that line, an on-type formatter that appends ` // typed` once `;` ends a
line, linked editing of the two names in `<name>...</name>`, and `#rrggbb` colours, written back the
same way when picked. `SplitModeInlineCompletionTest` uses its inline completion: `greet(` before the
caret is finished as `greet("world")` over the rest of the line, and accepting it runs
`probe.inlineAccepted`. `probe.inlineState` lists the trigger kinds it was asked with and what was
accepted.

The UI probe in `extension.js` is what `SplitModeViewsTest` drives. Its "Probe UI" container has a
tree whose item opens a webview panel (`webview.js`), which posts its clicks and saves their count with
`setState`; `probe.reloadPage` clears and sets the page's HTML again. The panel's page also has a
50-message burst, a `Uint8Array` the extension echoes back reversed, and a `command:` link to
`probe.linkFollowed`; the extension posts it a message as soon as it is made. `probe.uiState` and
`probe.webviewState` report what the extension saw. The "Probe Webview" container holds a webview view,
whose resolutions, clicks and visibility changes `probe.viewState` reports.

Packaged by `gradlew buildWorkbenchFixture`; plain JavaScript, no dependencies.
