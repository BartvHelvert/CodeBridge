# Test fixtures

These files are shared by the `shared`, `backend` and `ui-test` suites; `protocol` needs none of them,
being tested against traffic captured inline in its own sources. They are not packaged in the
plugin. Tests find them through the `codebridge.test.fixtures` property the build sets, wherever their module is; run the commands below from
the repository root.

Each fixture carries its own README explaining what it establishes and why it is not redundant with
the others. Read that before changing or deleting one.

| Fixture                                                                     | Used by                                                                                                                                                  | What it is for                                                                                                                                                 |
|-----------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`exthost/probe-extension/`](exthost/probe-extension/README.md)             | `ExtensionHostSessionTest`, `RemoteExtensionHostTest`                                                                                                    | The smallest proof a host booted: activation, console forwarding and a message through the real VS Code API                                                    |
| [`exthost/codelldb-1.12.3.vsix`](exthost/README-codelldb.md)                | `RealExtensionSurveyTest`, `DebugAdapterDiscoveryTest`, `ExtensionStoreTest`                                                                             | The one extension we did not write: real activation, adapter discovery and offline installation                                                                |
| [`exthost/lsp-bundled-extension/`](exthost/lsp-bundled-extension/README.md) | `LanguageFeaturesIntegrationTest`, `SplitModeExtensionTest`, `SplitModeStorefrontTest`                                                                   | A published extension's shape -- one bundled `dist/extension.js`, no `node_modules`, `onLanguage:` activation                                                  |
| [`workbench-probe-extension/`](workbench-probe-extension/README.md)         | `SplitModeWorkbenchTest`, `SplitModeWindowTest`, `SplitModeTestingTest`, `SplitModeActionsTest`, `SplitModeLanguageFeaturesTest`, `SplitModeRuntimeTest` | The workbench calls GitLens and Error Lens make, window UI, a test controller and editing providers, in colours a screenshot of the client can be searched for |
| [`exthost/lsp-socket-extension/`](exthost/lsp-socket-extension/README.md)   | `LanguageFeaturesIntegrationTest`                                                                                                                        | The same controlled server on a socket, so only the transport differs                                                                                          |
| [`textmate-extension/`](textmate-extension/README.md)                       | `SplitModeTextMateTest`                                                                                                                                  | A grammar-only extension, for syntax colours forwarded to the client and their lifecycle                                                                       |
| [`bundling-plugin/`](bundling-plugin/README.md) | `SplitModeBundledExtensionTest` | An IntelliJ plugin that is a VS Code extension and nothing else, as a vendor would publish one, wrapping `lsp-bundled-extension` |

CodeLLDB is redistributed under MIT. Its archive ships no licence file, so the required notice lives
in [third-party notices](../../NOTICE.md); see its [fixture README](exthost/README-codelldb.md) before
moving or repackaging it.

### Realistic fixtures

The fixtures that remain are realistic on purpose. Earlier ones used unbundled dependencies and
`"*"` activation so the bridge could be brought up without extension variability in the way; two
bugs hid behind that, and both were found the day those fixtures were replaced.

`lsp-bundled-extension` is the realistic counterpart. It is webpacked to a single `dist/extension.js`
with no `node_modules` beside it -- exactly what a VSIX installs -- and activates on
`onLanguage:codebridgebundled`. Regenerate its bundle with `npm install && npm run build` in that directory,
then **delete `node_modules`**: leaving it makes the fixture unbundled again. Nothing checks this, so
look before committing. `lsp-socket-extension` keeps its dependencies, because only its transport is under test.

Run tests with `./gradlew :shared:test :backend:test` (`.\gradlew.bat` on Windows).
This command can start external hosts or download native debugger payloads when their prerequisites
are available; it is not an isolated unit-only suite. Everything except the native host tests below
runs without prerequisites.

## Runtime prerequisites

The extension-host integration tests need a runtime, and they resolve it the way the plugin does,
through `VsCodeRuntime`. A test JVM has no installed plugin to find it in, so the build points it at
the same pinned VSCodium REH package the plugin variant ships, unpacked under
`build/vscodium/host`. Nothing has to be installed by hand.

- Unpacked by an earlier test run? Nothing to set.
- First time? `CODEBRIDGE_TEST_LSP_HOST=1` lets the build download (~113 MB) and unpack it. Without
  it these tests skip rather than fetch it behind your back, and an offline machine skips rather
  than fails.
- `CODEBRIDGE_VSCODE_HOME` still overrides everything, for working on a host build of your own.

The [complete variable reference](../../docs/runtime-and-lsp.md#runtime-selection) lists the rest. Node
dependencies are not checked in; prepare a fixture that needs them with
`npm ci --ignore-scripts --prefix <fixture>`.

## Native host tests

These start a real extension host, so they are opt-in. The same variable that unpacks the runtime
enables them:

```powershell
npm ci --ignore-scripts --prefix src/test-extensions/exthost/lsp-socket-extension
$env:CODEBRIDGE_TEST_LSP_HOST = '1'
.\gradlew.bat :shared:test --tests '*LanguageFeaturesIntegrationTest' --rerun-tasks
```

The first run downloads the runtime into `<Gradle user home>/codebridge/vscodium`; later runs, `clean` and other
checkouts reuse it.

`lsp-bundled-extension` needs no `npm ci`: it is committed bundled, which is the property under test.
Use shell `export` syntax on Linux/macOS. These tests launch child processes and connect OS pipes;
an environment that forbids either will fail them with `EPERM` rather than skip them.

## Split mode

Everything else in this repository runs a `CodeInsightTestFixture`, which is a single in-process
IDE. There is no client in it, so nothing in it can answer the question split mode raises: the
plugin's language features are computed on a backend whose module the JetBrains Client does not
load, and they reach the user only if the platform forwards them.

That has gone wrong here before. Routed through `intellij.platform.lsp`, everything worked on the
backend and the client window rendered nothing, because the module that draws it has no frontend
half -- a failure that looked like success in every log worth reading.

The `ui-test` module exists for that question. It uses JetBrains' `ide-starter` framework, which
launches both processes and lets a test read the client's own UI model back.

Its tests are grouped by the plugin whose behaviour they check, and each installs that plugin and
what it depends on, nothing more (`Installed` in `PluginUnderTest.kt`):

| Package                         | Installs                           | So that                                                                     |
|---------------------------------|------------------------------------|-----------------------------------------------------------------------------|
| `me.helvert.codebridge.uitest.runtime` | The runtime alone                  | A runtime feature that works only beside CodeBridge fails here              |
| `me.helvert.codebridge.uitest.bundled` | The runtime and Bundled Extensions | A vendor's bundled extension is checked as a user without CodeBridge has it |
| `me.helvert.codebridge.uitest.vscode`  | CodeBridge, and the runtime        | `.vscode/` files, the Extensions page and the full install are checked      |

Without CodeBridge a project's `.vscode/settings.json` is not read, so a runtime test sets what it
needs in the backend's user settings (`writeUserSettings`). Run one plugin's tests with, for example,
`:ui-test:test --tests 'me.helvert.codebridge.uitest.runtime.*'`.

`SplitModeTextMateTest` installs the [syntax-only grammar fixture](textmate-extension/README.md)
in the backend store and checks keyword and string colours in the client, then disable, enable
and uninstall in the same open editor. Syntax colours arrive as range markup even though the
client has no local grammar; inspecting only its lexer misses them. Run it alone with
`:ui-test:test --tests '*SplitModeTextMateTest'`; the UI tests run only when that task is named.

```powershell
.\gradlew.bat buildPlugin              # close any sandbox IDE first; a running one locks the jar
.\gradlew.bat :ui-test:test
```

Opt-in, and not part of `freshTest`. The first run downloads and unpacks IDEA into `out/`, which is
a few GB and about a minute; after that a test is roughly twenty seconds, because the thin client
ships inside the same distribution and needs no download of its own.

`SplitModeSmokeTest` is the floor: the client really is started as
`idea64.exe thinClient tcp://127.0.0.1:...` against the backend, and both `idea.log`s record
`Loaded custom plugins: CodeBridge`.

`SplitModeDrawingTest` asks the question the split actually endangers. It pushes a `$changeMany`
into `HostedLanguageFeatures` **on the backend** and then asks the *client's* editor what it is
showing -- and the annotation is there. That is the failure mode the LSP route died of, now
covered automatically. The push is hand-fed rather than earned from an extension, for the same
reason `DiagnosticsHighlightingTest` hand-feeds it: the extension host is a separate axis, and
mixing the two would leave a failure ambiguous between "the host did not answer" and "the client
did not draw".

Two things about the driver were wrong once each, and both are worth knowing before adding more:

- **Projects live on the backend.** `driver.singleProject()` answers `No projects are opened`,
  which is not a bug -- it is the split being what it claims. Pass `RdTarget.BACKEND`, and give
  the driver time: the client attaches after the driver connects.
- **`@Remote` must name the content module**, not just the plugin. `me.helvert.codebridge.runtime` alone
  resolves to the main plugin's classloader, which cannot see `me.helvert.codebridge.language.*` and answers
  `No such class`. The driver splits the coordinate on `/`, so it is
  `me.helvert.codebridge.runtime/CodeBridge.backend`. This is the same module-boundary trap the LSP route
  fell into, in a different disguise.

Opening the file also goes through a starter `CommandChain`, not the driver's own `openFile`:
that helper resolves the path against a project the client cannot see and fails inside the IDE.

`SplitModeUntitledTest` creates an untitled buffer on the backend -- an in-memory file, as
`openTextDocument({ content })` makes one -- and shows it through the real `showTextDocument`
path, which asks the client to open it. It passes only if the backend hears of the client's editor
and the client's editor shows the buffer's text: the one part of untitled buffers no in-process
fixture can reach, since the file has to cross the split before the client can open it.

`SplitModeExtensionTest` installs the bundled probe VSIX into the isolated backend's extension
store before opening its contributed file type. It checks diagnostics, Quick Documentation and
completion in the client, types an unsaved edit, checks the changed server version and cleared
diagnostic, and opens the extension's output tab. It disables automatic saving in both processes
and verifies that the file on disk still contains the original text. The server sends a startup
log message so its output channel is created even when startup succeeds.

Run just this workflow with:

```powershell
.\gradlew.bat :ui-test:test --tests '*SplitModeExtensionTest'
```

The task builds the build machine's plugin variant, which carries the pinned VSCodium runtime, and
the probe VSIX; `CODEBRIDGE_VSCODE_HOME` can point the backend at another copy of that runtime.
The test runner uses JDK 25 to match the plugin modules used for VSIX installation.

The rest of the suite drives the client the same way, each against the probe extension that
exercises it:

| Test                            | What the client does                                                                                                 |
|---------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `SplitModeStorefrontTest`       | The VS Code Extensions page: enable, disable per workspace, uninstall and the gear menu, checked against the backend |
| `SplitModeActionsTest`          | Rename, Reformat Code and a quick fix from an extension, editing the client's document                               |
| `SplitModeLanguageFeaturesTest` | Extend Selection, document links, linked editing, on-type formatting and colour swatches                             |
| `SplitModeWorkbenchTest`        | Error Lens and GitLens decorations, painted in the client, and a diff against a file system provider                 |
| `SplitModeWindowTest`           | Status bar items, messages, quick picks and input boxes, answered in the client                                      |
| `SplitModeViewsTest`            | A contributed tree view's commands and refresh, and a webview whose state survives a reload                          |
| `SplitModeTestingTest`          | Gutter Run markers for an extension's tests, and the test runner's results                                           |
| `SplitModeRuntimeTest`          | Extension tasks stopped and rerun, and debugger controls reaching the adapter                                        |

### Doing it by hand instead

Fifteen minutes, and it needs no framework:

```powershell
.\gradlew.bat buildLspFixture    # build/fixtures/codebridge-lsp-probe.vsix
.\gradlew.bat runIde             # split mode by default; -PsplitMode=false for a monolith
```

In the client window, install the probe VSIX under **Settings -> VS Code Extensions**, enable it,
and open a file named `example.codebridgebundled` whose first line contains `ERROR`.

| Do this | Expect | Proves |
|---|---|---|
| Open the file | A plain-text editor, not an unknown file type | File type overrider |
| Look at line 1 | `ERROR` underlined | The annotator forwards |
| Quick Documentation on a word | `...server PID N; version 1: ERROR replace...` | The documentation target forwards |
| Edit the line, pause | The underline follows, and the hover reports the new version and text | Document sync crosses the split |
| Ctrl+Space | `version-N` and `wrap`, with N matching the edits | Completion forwards, and sync again |
| **View -> Tool Windows -> CodeBridge Output** | A tab named after the extension's channel | The tool window forwards |

The backend is the half that needs the extension host. Its sandbox gets the build machine's plugin
variant, runtime included; `CODEBRIDGE_VSCODE_HOME` in its environment substitutes another.

## What the suites cover

| Area                                                  | Covered by                                                                                                                                                                                                        |
|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Workbench protocol over child stdio and over a socket | `LanguageFeaturesIntegrationTest`, against the bundled and socket fixtures: hover, completion, definition and pushed diagnostics, with no runtime or client patching                                              |
| Document synchronization                              | `LanguageFeaturesIntegrationTest` against a real host, reading back the version and line the server holds; `DocumentSyncTest` for the editor half                                                                 |
| Completion and Go To Declaration, drawn in an editor  | `CompletionTest` and `DefinitionTest` in a light IDE, feeding wire payloads; one case goes through `GotoDeclarationAction`, the only way to show the platform consults this extension point for a plain-text file |
| Pushed diagnostics drawn as highlights                | `DiagnosticsHighlightingTest` in a light IDE, `SplitModeDrawingTest` in a split client                                                                                                                            |
| Output channels and progress                          | `OutputChannelsTest` and `ProgressTest` for the bookkeeping, `SplitModeExtensionTest` for the output tab in a split client                                                                                        |
| A real published extension                            | `RealExtensionSurveyTest` and `DebugAdapterDiscoveryTest`, with CodeLLDB                                                                                                                                          |
| Split mode                                            | The `ui-test` suite above                                                                                                                                                                                         |

Native host tests skip themselves without a runtime, and the split-mode suite is opt-in, so check
skip counts when reporting a run.
