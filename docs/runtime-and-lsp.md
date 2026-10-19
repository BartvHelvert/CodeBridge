# Runtime and language-support reference

This page explains runtime selection, how language support reaches IntelliJ, and how it fails.
All runtime paths and environment variables below refer to the **backend machine** in split mode.

## Runtime selection

The runtime is not configurable. The plugin runs one pinned VSCodium build, currently
`1.135.06055`, and ships it: CodeBridge Runtime is published as six distributions, one per OS and
architecture (`intellijPlatform.nativeVariants`, IntelliJ Platform Gradle Plugin 2.19+, which needs
`sinceBuild` 261 or later), and each carries its own platform's REH package under
`<plugin>/runtime`. Extension-host rpcIds drift between builds and a mismatch misroutes calls
silently, so running anything but the build we test against is not offered.

The REH package carries only the built-in extensions that run in a host. The ones VS Code's workbench
loads -- `java`, `python`, `typescript-basics` and the rest, which say what language each file is --
it leaves out, so a `.java` file opened beside nothing but Error Lens or GitLens had no language, and
was never mirrored. The build takes their manifests, and nothing else of them, from the same release's
web package (`reh-web`) into `runtime/workbench-extensions/`, apart from `extensions/` so the host
loads what it always has. `BuiltinLanguages` reads both, and a file is opened as the closest claim:
a name outright, then the longest extension, as VS Code ranks them.

**Settings → Tools → CodeBridge** reports that state and nothing else: which runtime is running and
the directory it lives in. There is no path to set, no Detect, and no Download or Remove button.
Extension installation is on the separate **Settings → VS Code Extensions** page.

Resolution order is therefore short:

1. `CODEBRIDGE_VSCODE_HOME`, for tests and development.
2. The bundled runtime: the `codebridge.runtime.dir` system property if set (the build sets it for
   test JVMs and `runIde`, whose sandboxes carry no runtime), otherwise `runtime/` in the plugin's
   own directory.

### How the build produces it

The pin and the SHA-256 of each platform's archive live in `src/runtime/plugin/build.gradle.kts`.
`downloadRuntime_<platform>` fetches an archive into `.gradle/vscodium/<version>` (outside `build`,
so `clean` keeps it) and fails on a hash mismatch. `repackRuntime_<platform>` moves its contents
under `runtime/`, archive to archive, and `buildPluginVariants_<os>_<arch>` adds that to the
variant. Bumping the pin means replacing the version and every hash.

**Executable bits are the fragile part.** IntelliJ restores the Unix modes a plugin ZIP records when
it installs one, so `node`, `spawn-helper` and `rg` are executable on macOS and Linux only if the ZIP
says so. Gradle 9 archive tasks normalize every file to `644` by default; both the repack and the
variant ZIPs call `useFileSystemPermissions()`, which keeps the modes from the source tar entries
even when building on Windows. Extracting to disk first would lose them on a Windows build machine.
Check with `zipinfo` (or Python's `zipfile`) that `CodeBridgeRuntime/runtime/node` is `755` before
publishing.

Only the variant ZIPs carry a runtime. `runIde`, `runIdeBackend`, `runIdeFrontend`, the test
sandboxes and the split-mode UI tests run the build machine's runtime unpacked once into
`build/vscodium/host` (`:runtime:extractHostRuntime`) and find it through `codebridge.runtime.dir`: a sandbox
holding its own copy would delete and rewrite ~2,800 files every time the plugin's code changes. The
plain `buildPlugin` ZIP has no runtime.

Windows on ARM has no published package. Its variant is still built, because the six come as a set,
and ships without a runtime; the settings page says the extension host cannot run there.

Earlier versions downloaded the runtime into `<IDE system>/vsxbridge/runtime` on first start.
`LegacyRuntimeCleanup` deletes that directory at startup.

| Variable                     | Consumer                                          | Meaning                                                                                                                                                      |
|------------------------------|---------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `CODEBRIDGE_VSCODE_HOME`     | Runtime resolution, in the IDE and in tests alike | An installation to run instead of the bundled one                                                                                                            |
| `CODEBRIDGE_TEST_LSP_HOST=1` | The build and the host tests                      | Lets `:runtime:extractHostRuntime` download and unpack the build machine's runtime into `build/vscodium/host`, and enables the tests that start a real extension host |

The tests resolve a runtime through `VsCodeRuntime`, the same class the backend uses, so they run on
the same pinned REH package the plugin ships. A runtime already unpacked is used as-is, so only the
first run needs the variable -- `gradlew test` never reaches for 113 MB on its own.

## Why not IntelliJ's LSP client

The platform ships `intellij.platform.lsp` and `intellij.platform.lsp.impl` as backend modules and
**no frontend counterpart**, where every other split-aware feature ships one
(`intellij.json.frontend.split`, `intellij.database.frontend.split`,
`intellij.platform.debugger.impl.frontend`). Routed through it, language features worked perfectly on
the backend -- host started, extension activated, server launched, LSP client initialized, all
visible in `idea.log` -- and rendered nothing in a JetBrains Client window. **Settings → Languages &
Frameworks → Language Servers** was absent there too, because the module that draws it is not loaded.

That is why diagnostics are an annotator, hover is a documentation target and Go To Declaration is
an implicit reference (`CodeBridgeSymbolReferenceProvider`): all of them are ordinary backend-computed features the platform already
forwards to a split frontend. It also means the
plugin no longer depends on the LSP module at all, so it no longer needs an IDE that has one.

Observed on 2026-09-11 against IntelliJ IDEA 2026.1.3, and re-checked on 2026-09-14 against the
2026.3 EAP build 263.4732.28 (the build now pins 263.7307.44): `lib/` ships `intellij.platform.lsp.jar`,
`intellij.platform.lsp.impl.jar` and `intellij.platform.lsp.impl.structureView.jar`, and nothing
matching `*lsp*frontend*` exists anywhere in the distribution, while the three comparisons above all
still ship theirs. Re-run that check before assuming this section is current.

## The workbench protocol

This is how diagnostics reach the editor. It works the way the VS Code workbench does, and needs
none of the machinery the removed RPC bridge depended on -- no runtime patch, no language-client
instrumentation, no proxy, and no interest in the transport:

| Step | Call |
|---|---|
| The extension registers a provider | `MainThreadLanguageFeatures.$registerHoverProvider(handle, selector)` arrives at the main-thread handler |
| The extension publishes diagnostics | `MainThreadDiagnostics.$changeMany(...)` arrives the same way; no provider protocol at all |
| A document is opened | `ExtensionHostDocuments` mirrors it with `$acceptDocumentsAndEditorsDelta`, and the extension's own client sends `didOpen` upstream |
| The user types | `ExtHostDocuments.$acceptModelChanged(uri, event, isDirty)` carries the buffer, and the client sends `didChange` |
| The file is saved | `ExtHostDocuments.$acceptModelSaved(uri)`, which also clears the document's dirty flag inside the host |
| The file is closed | `$acceptDocumentsAndEditorsDelta` again, with `removedDocuments` |
| The editor asks for a hover | `ExtHostLanguageFeatures.$provideHover(handle, uri, position, context)`, sent as a cancellable request so the host appends the token |
| The editor asks for completions | `ExtHostLanguageFeatures.$provideCompletionItems(handle, uri, position, context)`, the same way, followed by `$releaseCompletionItems` |
| The user follows a definition | `ExtHostLanguageFeatures.$provideDefinition(handle, uri, position)`, the same way. The reply is a `LocationLink` per place, whose `targetSelectionRange` is where the caret goes |
| The caret settles on a symbol | `ExtHostLanguageFeatures.$provideDocumentHighlights(handle, uri, position)`, drawn as read and write usages |
| The intention menu asks what applies | `ExtHostLanguageFeatures.$provideCodeActions(handle, uri, range, {trigger: 1})`; a chosen action without an edit is filled in by `$resolveCodeAction`, its command run with `ExtHostCommands.$executeContributedCommand`, and the list let go with `$releaseCodeActions` |
| An extension applies an edit | `MainThreadBulkEdits.$tryApplyWorkspaceEdit(edit, undoGroup, isRefactoring)`, answered `true` or `false` |
| The user reformats | `ExtHostLanguageFeatures.$provideDocumentFormattingEdits(handle, uri, options)`, or `$provideDocumentRangeFormattingEdits(handle, uri, range, options)` for a selection |
| A document opens, settles after an edit, or a provider reports a change | `ExtHostLanguageFeatures.$provideDocumentSemanticTokens(handle, uri, previousResultId)`, whose reply is a binary `VSBuffer`, followed by `$releaseDocumentSemanticTokens` |
| A provider is withdrawn | `MainThreadLanguageFeatures.$unregister(handle)`, which is the same call whatever the provider was; the named `$unregisterHoverProvider` form exists but extensions do not reach it |

It passes against `lsp-bundled-extension` -- a webpacked extension with no `node_modules`, which the
removed RPC path could not instrument at all -- and the hover it returns carries the version and line the
server was actually sent, so document mirroring is proven end to end.

## Document synchronization

What an extension's server must see is the editor's buffer, not the file on disk. `ExtensionHostDocuments`
holds the host's copy and is the only thing that writes to it; `HostedLanguageServers` supplies the
editor events and `HostedLanguageFeatures` maps files to the URIs the host knows them by.

| Signal | Source | Sent as |
|---|---|---|
| Opened | `CodeBridgeFileOpenListener.fileOpened`, once an enabled extension claims the file | `addedDocuments`, version 1 |
| Edited | a `DocumentListener` on the editor-factory multicaster | `$acceptModelChanged`, version incremented |
| Saved | `FileDocumentManagerListener.afterDocumentSaved` | `$acceptModelSaved` |
| Closed | `CodeBridgeFileOpenListener.fileClosed`, once the last split for the file has gone | `removedDocuments`, or only `removedEditors` while an extension holds it |
| Opened by an extension | `MainThreadDocuments.$tryOpenDocument`, handled by `HostedDocuments` | `addedDocuments` with no editor; `addedEditors` alone if the user opens it later |

Both listeners are application-wide, because the platform has no project-scoped document listener:
every edit in every project arrives and is dropped unless this project mirrored that file.

Three things are deliberate, and each one was a way to get it wrong:

- **Versions are counted by `ExtensionHostDocuments`**, not taken from IntelliJ. Modification stamps are
  not the small ascending integers VS Code's editor model expects, and nothing rejects a bad one --
  a version that went backwards would simply stop the client from sending the edit upstream.
- **Whole-document changes.** Several IntelliJ endpoints report one keystroke, edits are coalesced
  before they are sent, and a file re-read from disk arrives as a replacement anyway; sending the
  text we have makes all three the same operation. Text that matches what the host already has is
  not sent at all.
- **Everything runs on the project host's single worker thread**, which is also where documents are
  opened, so the host is never told about an edit before the document it belongs to. The editor
  thread only queues, and at most one sync is queued per file: the queued task reads whatever the
  document holds when it runs, which is what makes dropping the intermediate keystrokes safe.

Getting this wrong is invisible from the IDE side -- a server answers from a stale copy rather than
failing -- so the fixtures answer hovers with the version and line they were actually sent.

Results come back in VS Code's editor shapes rather than LSP's: 1-based `startLineNumber` and
`startColumn`, and `MarkdownString` values whose plaintext spaces are escaped to `&nbsp;`.
Translating those, per feature, is what this route costs instead of a protocol bridge.

`HostedLanguageFeatures` holds what arrives and `CodeBridgeDiagnosticsAnnotator` draws it. An annotator
rather than IntelliJ's LSP client, deliberately: annotations are ordinary highlighting, computed on
the backend and forwarded like any other, so unlike the LSP client they survive split mode.

`CodeBridgeLanguageFileTypeOverrider` gives claimed files a file type. Without one there is no document and
no PSI, so nothing can annotate them -- the server runs, reports problems, and the editor stays
blank.

The type is a `CodeBridgeLanguageFileType` per contributed language, named `CodeBridge.<id>` and shown under the
language's first alias, so the status bar names the language rather than calling every contributed
file Plain Text or TextMate. A `fileTypeOverrider` rather than a declared `fileType`, because which
languages exist is known only once an extension is installed; the price is that the type is not in
the file type registry, so the platform cannot look it up by name and asks again rather than reading
a cache. It claims only names the IDE's own name table has no answer for, so an extension
contributing `.json` or `.py`, or a file the user associated by hand, is left alone.

The icon is chosen as VS Code chooses one. First its default file icon theme, Seti, which
`FileIcons` carries as `fileicons/mapping.properties`, keyed by language id, file name and file
extension: VS Code's own table, not one of ours. Each entry is drawn with JetBrains' icon for that
kind of file where there is one -- the IDE's own file type icons, then those of JetBrains' other
products -- and with Seti's glyph otherwise; `fileicons/LICENSE.txt` says where each came from.
`CodeBridgeFileIconProvider` applies the same lookup by name alone to files the IDE has no type of its own
for, which is how `Cargo.toml` gets a config icon with nothing installed that reads TOML.

Where the theme has nothing, the icon comes from `contributes.languages[].icon`, VS Code's own
light/dark pair, which VS Code too reads only as that fallback. `CodeBridgeLanguageIcon` picks the variant
per theme on every call, so it follows a theme switch, and reads each image once on first paint.
They are untrusted manifest paths, resolved only to files inside the extension directory, and scaled
to 16px -- with `IconUtil.scale` and not `resizeSquared`, which clamps its factor to a tenth and so
reported 16 while painting 60 for a 600px source.

A language with neither gets `CodeBridgeMonogram`: a drawn disc carrying the language's initial, in a
colour derived from its id so each language looks like itself and unlike its neighbours. Outlined
rather than filled, following the IDE's own icons -- a coloured ring with a wash inside -- because a
solid 16px tile is the heaviest thing a file tree row can hold and reads as shouting beside the
platform's own. The extension's marketplace `icon` is not used -- VS Code never draws it on a file,
and having no dark variant it is usually a white block on a dark tree.

The monogram is written out as a small SVG under the system directory and loaded through
`IconLoader`, rather than painted by a custom `Icon`. In split mode the backend must describe every
icon to the frontend, and it can only describe images it loaded; a painted icon logged "Failed to
serialize a model for icon" and never reached the frontend. The letter is its glyph outline in the IDE's
label font, so the SVG renderer's font choice never enters into it.

An extension's own icon theme (`contributes.iconThemes`) is not read.

An overrider, and not the content detector this replaced, because a detector is never reached for
these files. `TextMateFileType` implements `FileTypeIdentifiableByVirtualFile`, which the platform
consults ahead of detection, so it claims every name it has a grammar for: with rust-analyzer
installed, a `.rs` file was typed `textmate` and the detector was not asked at all. Overriders are
consulted before both. `CodeBridgeLanguageFileTypes` holds the decision behind a cache keyed by the
extension store's revision, because the platform asks for a file type far more often than an
extension is installed.

Taking the file type means owing the file what TextMate was giving it, which `CodeBridgeFileLanguageProvider`
supplies -- a point rather than a call, since the TextMate plugin is not in every IDE.
`CodeBridgeTextMateFileLanguage` answers with the `textmate` language wherever a bundle has a grammar for
the name, and the file type carries it: the syntax highlighter, parser, commenter and spelling
strategy are all registered for that language and resolve the grammar from the file's own name, so
they land on the same file under the contributed language's name. The editor highlighter is the
exception, keyed by file type name, so `CodeBridgeTextMateFileTypes` registers TextMate's own provider
against each type as it is minted -- without it the platform builds its default lexer highlighter,
which packs token types into a short and fails outright on the many a TextMate grammar mints. A name
no grammar answers for is read as plain text, which is what these files had before any of this.

One thing TextMate does not give the file is Enter between brackets. The platform's
`EnterBetweenBracesFinalHandler` puts `}` on its own line only when the two brackets are different
PSI elements, and a TextMate file is a single leaf, so `{|}` became an indented `|}`.
`CodeBridgeTextMateEnterBetweenBraces` answers that test from the brackets the extension's language
configuration declares at the caret's scope.

A claimed file does not reliably end up plain text, and the two features answer that differently: a
grammar-backed file is read as `textmate`, and an extension contributing an extension the IDE
already knows -- `.json`, `.py`, `.md` -- keeps the IDE's own language, the overrider standing back.
`textmate` is a root language (`Language("textmate")`, no base) and so is `JSON`, so neither
inherits anything registered for `TEXT`.

**Diagnostics** are therefore registered once, with an empty `language`. That is `Language.ANY`'s id,
and `ExternalLanguageAnnotators` resolves through `LanguageExtension.allForLanguageOrAny`, so one
declaration covers every language the IDE has. `HostedLanguageFeatures.problemsFor` returns on an
empty map before touching the file, because that registration means it runs on every file the daemon
looks at. Before this, a diagnostic pushed at a `.json` file was stored and silently never drawn.

**Completion** cannot do the same. `CompletionContributor.forLanguage` resolves by exact key through
`LanguageExtension.forKey`, which widens to meta languages but never to `Language.ANY`, so every
language it should answer for has to be named. Only `TEXT` is named in the descriptor, for plain-text
files.

The rest are learned, by `CodeBridgeCompletionLanguages`. `HostedLanguageServers.attach` already decides
whether an installed extension claims the file being opened; where it does, it hands the registrar
that file's `FileType`, and a `LanguageFileType` gets the contributor registered for its language.
That is deliberately the opened file rather than the manifest pattern:
`FileTypeManager.getFileTypeByFileName` consults name matchers only, and a file claimed by a
TextMate grammar is claimed through `FileTypeIdentifiableByVirtualFile`, which that path never
reaches. The opened file has already been through the whole resolution, so it knows.

`TEXT` is skipped, being declared already; adding it again would offer every item twice. The set only
grows: an uninstalled extension does not make a registration wrong, because
`CodeBridgeCompletionContributor` returns immediately unless some extension has offered to complete. It is
an application service, because the extension store and the extension point are both
application-wide and a project-level owner would register once per open project. Registration
happens before the host is started, so a slow startup cannot lose the first lookup, and
`completion.contributor` is declared `dynamic="true"`, so none of this needs a restart.

The optional `textmate.backend` module supplies `CodeBridgeTextMateBundles` through
`com.intellij.textmate.bundleProvider`. It depends on plugin `org.jetbrains.plugins.textmate` and
content module `intellij.textmate`; the plugin dependency alone does not expose the provider API
at runtime on the pinned build. Keeping these dependencies outside `backend` means switching
TextMate off does not unload CodeBridge's RPC endpoints, launch configuration or workbench.
Completion needs no additional registration here: `CodeBridgeCompletionLanguages` learns `textmate` from
opened files.

An unpacked VSIX is already a VS Code TextMate bundle: `BundleType.detectBundleType` recognizes its
root `package.json`. The provider supplies enabled installed package roots. An application-wide
installation listener coalesces changes and calls `reloadEnabledBundles()` off the editor thread.
Grammar-only replacements also send a file-type change event: TextMate itself sends one only when
filename-to-scope mappings change. Updates therefore use the current package generation, while
disabled or uninstalled extensions stop contributing bundles. No user bundle paths are persisted.

In split mode, the backend computes syntax colours and IntelliJ forwards them as range highlights
to the client. The client's `TextMateService` has no copy of the installed grammar, and its local
lexer reports no colours; inspecting only that lexer gives a false negative. `SplitModeTextMateTest`
checks the client's keyword and string foreground colours and their removal/restoration on disable,
enable and uninstall. No frontend grammar transfer is needed for this highlighting path. Grammar-claimed files use type
`textmate`, while `CodeBridgeLanguageFileType` stays
the fallback for contributed languages without a grammar or with TextMate disabled.

`CodeBridgeHoverTargetProvider` answers Quick Documentation by calling `$provideHover`. A documentation
*target* rather than a `DocumentationProvider` because the offset matters: for a plain-text file the
PSI element is the whole file, so the caret position would otherwise be lost. Hover text is markdown,
rendered to HTML by `HoverMarkdown` with fenced code highlighted.

`CodeBridgeCompletionContributor` offers what an extension would complete. See
[completion](#completion) for the part of it that is not obvious.

`CodeBridgeFileOpenListener` starts all this when a claimed file is opened. It replaced
`LspServerSupportProvider.fileOpened`, which tied the trigger to the LSP module.

## Completion

Completions come back under single-letter keys -- `{x: cacheId, a: defaultRange, b: items}`, each
item `{a: label, b: kind, c: detail, d: documentation, e: sortText, f: filterText, h: insertText,
i: insertTextRules, j: range, n/o/p: command}`. That is read off the runtime bundle, like the
rpcIds; there is no published description of it.

Four things are worth knowing before changing any of it:

- **The prefix comes from the extension, not from IntelliJ.** The result's default `insert` range
  runs from the start of the word the extension is completing to the caret. For a plain-text file
  IntelliJ would work the boundary out itself and get it wrong after a dot or a dash, and a lookup
  that filters out every item looks exactly like a server that offered nothing.
- **Order is preserved.** The extension has already ranked the items; IntelliJ would otherwise
  re-sort them by its own relevance, so each element carries a descending priority.
- **Snippets become live templates.** `insertTextRules` bit 4 means the insert text is a VS Code
  snippet, whose syntax IntelliJ does not speak. `Snippets.expand` translates it. A snippet without
  stops is inserted as its default text, the caret where `$0` was; one with tab stops is inserted as
  a live template whose variables are its stops, so Tab walks them in VS Code's order, offers a
  choice's options, and ends on `$0`. A stop nested in a placeholder becomes part of the outer stop's default text, since
  template variables cannot nest. Only the backend can start a template, so such an item is always
  inserted there.
- **A command runs once its item is inserted**, as in the workbench. A command whose arguments the
  host cannot send -- CodeLLDB's "CodeLLDB: Cargo ...", given the document -- stays in the host with
  its list: `n` names it, and `o` is the host's command that runs it given `n`. The item is
  inserted by the client, as one without a command is (`CodeBridgeCommandInsertHandler`), and the backend
  keeps the command under an id (`CodeBridgeCompletionCommands`). The client then asks for it by id, with
  the document's length and hash. The backend waits up to two seconds for its copy of the document
  to match, sends the insertion to the host, and runs the command on the worker that sends edits.
  An item whose snippet has tab stops is still inserted by the backend, which runs the command
  itself.

The documentation of the item the user settles on comes from `$resolveCompletionItem`, the second
round trip, when its provider resolves items (`CodeBridgeCompletionDocumentation`); what the list carried is
the fallback. rust-analyzer fills documentation in only on resolve. A list is released once read,
unless its provider resolves items or one of its items runs a command: then it is kept until the
provider answers another lookup, or for a minute, since the backend is not told when a split-mode
lookup closes.

## Output channels and progress

Both exist because an extension that cannot say what it is doing is indistinguishable from one that
is broken. `RealExtensionSurveyTest` records eleven progress calls and an output-channel
registration from CodeLLDB's activation alone.

**Output** does not work the way it looks. The host never sends the text: it writes a log file of
its own, and `$update(id, mode, till)` says how far it has got, so `HostedOutputChannels` reads
that file from the offset it last stopped at. It also only sends `$update` for the channel it
believes is *visible* -- one at a time, chosen by us with `ExtHostOutputService.$setVisibleChannel`
-- so selecting a tab in the **CodeBridge Output** tool window reaches back into the host rather than just
switching a panel. `$register` is the one call whose reply the host reads; it answers with the
channel id every later call names.

**Progress** is one IntelliJ background task per handle, blocking until the extension reports it
finished. Cancelling the task calls `ExtHostProgress.$acceptProgressCanceled`, so the work stops
rather than the bar merely disappearing. The task is started from a pooled thread, because a
headless IDE runs a background task synchronously and would otherwise block the RPC reader on a
latch only a later call from that same reader can release.

The plugin answers 37 of the host's 87 `MainThread*` services, some of them partly. That is also why unrelated
extensions
fail: a workbench command or task service that is not implemented stops an extension regardless of
how language support is wired. [Workbench coverage](workbench-coverage.md) lists what is left.

## Extension installation and lifecycle

Installations take effect immediately without waiting for Settings Apply. Replacing an extension
selects the new installed version; canceled or failed extraction preserves the previous install.
Enable/disable and uninstall controls are on the Installed tab. Dependencies and extension-pack
members must be installed separately. Platform checks use the backend machine; VS Code engine
compatibility is displayed but not enforced. Old package generations are retained pending cleanup.

Installation stores files and metadata; host startup and extension activation are separate steps.
Opening matching files or starting a debug session uses enabled extensions in a project host.

## Files and logs

| Location                               | Contents                                                                                                                                                                                                                                                                                     |
|----------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `<IDE config>/codebridge/extensions`   | Installed extensions, registry and retained package generations                                                                                                                                                                                                                              |
| `<plugin directory>/runtime`           | The bundled REH runtime                                                                                                                                                                                                                                                                      |
| `<IDE system>/codebridge/hosts/host-*` | Per-session project-host storage and private extension copies. One per started host, swept at the next host start rather than when it stops, so the last host's `exthost-stdout.log` stays readable and a crash leaves nothing behind either. Was `vsxbridge/lsp`, which is removed on sight |
| `host-*/logs/exthost-stdout.log`       | Host stdout/stderr; also inspect `exthost.log` and output-channel logs in `logs`                                                                                                                                                                                                             |
| `<OS temp>/codebridge-language-features*`     | Storage and host logs of `LanguageFeaturesIntegrationTest`, the native integration test                                                                                                                                                                                                      |

Project-startup and endpoint errors also go to the IDE backend's `idea.log`.

## Troubleshooting

| Symptom                                                      | Check or next step                                                                                                                                                                                                                                                        |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A runtime build hash mismatch while building                 | `downloadRuntime_<platform>` checks each archive against the hash pinned in `build.gradle.kts`. Delete the partial download under `.gradle/vscodium` and build again; do not edit the hash to accept an unknown archive.                                                  |
| An extension activates but provides nothing                  | Check that the extension is enabled and its activation completed, in the backend `idea.log` and the extension's own log in **CodeBridge Output**. Merely installing a VSIX is insufficient.                                                                                      |
| `connect EPERM` on a Windows named pipe                      | The host could not connect to the plugin's pipe: something in the environment, such as a sandbox or security software, forbids it.                                                                                                                                        |
| `spawn EPERM`                                                | The extension could not start its server process.                                                                                                                                                                                                                         |
| Native host tests skip                                       | They run only with `CODEBRIDGE_TEST_LSP_HOST=1`; rerun with `--rerun-tasks` after setting it.                                                                                                                                                                             |
| Diagnostics or hover show nothing                            | Check the backend `idea.log` for the host starting and the extension activating. If the extension activated, nothing registered a provider or pushed a diagnostic -- the file may not match its `contributes.languages`, which is matched by extension and filename only. |
| A test reports `UP-TO-DATE`, or passes with missing fixtures | Use `--rerun-tasks` after changing runtime/fixture inputs. Check test logs: some older integration tests return early when prerequisites are absent; LSP tests use explicit skip conditions.                                                                              |

Private extension copies are prepared again whenever the project host restarts.
