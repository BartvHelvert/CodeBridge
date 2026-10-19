# lsp-bundled-extension

A published extension's actual shape, and the fixture the core design claim rests on.

Webpacked to a single `dist/extension.js` with **no `node_modules` beside it**, activating on
`onLanguage:codebridgebundled`. That is what a VSIX installs. It starts a real `LanguageClient`, which
spawns `server.cjs` as an ordinary child process on stdio; the server's reply depends on the document
it received, so stale or missing document synchronization shows up as a wrong answer rather than as
silence.

**Used by** `LanguageFeaturesIntegrationTest`: the extension registers hover, completion and definition
providers over `MainThreadLanguageFeatures`, pushes diagnostics over `MainThreadDiagnostics` once a
document is opened, and answers `$provideHover`, `$provideCompletionItems` and `$provideDefinition`
-- with no patching of the runtime and none of the client. The server answers a definition with a
`LocationLink` whose selection sits at the column of the document version it holds, so the separate
selection range and the buffer it was computed against are both read back rather than assumed.

Also installed as a VSIX by `SplitModeExtensionTest`, which exercises diagnostics, hover,
completion and unsaved edits through a real split client. The server sends a startup log message
to create the LanguageClient's output channel for the tool-window check. See the parent README
for the workflow's validation status.

**Why bundled matters.** The earlier fixtures used unbundled dependencies and `"*"` activation, and
two bugs hid behind exactly those shortcuts (see "Realistic fixtures" in the parent README). No
test checks that this fixture is still bundled, so keep `node_modules` out of it by hand.

## Regenerating

```
npm install && npm run build
```

Then **delete `node_modules`**. Leaving it makes the fixture unbundled again, and nothing would
notice. The committed `dist/extension.js` is the artifact under test; `src/extension.js` is its source.

The bundle carries `vscode-languageclient` and its dependencies, whose licences are in
`THIRD-PARTY-NOTICES.txt`. If a regeneration changes the versions in `package-lock.json`, update that
file and the entry in the root `NOTICE.md` to match.
