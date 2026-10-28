# IntelliJ platform limitations

Places where CodeBridge cannot do what a VS Code extension asks for, or can only do it through
internal API, because the IntelliJ platform has no public way to -- not because the plugin has not
got to it yet. Gaps in the plugin itself are in [workbench coverage](workbench-coverage.md).

Each entry says what VS Code offers, what the platform offers instead, what CodeBridge does about
it, and what would need to change in the platform. They are written so the last part can go to
JetBrains as a feature request.

Checked against IntelliJ Platform 263 (2026.3, build 263.7031.23). An internal API that CodeBridge
calls is guarded by `LinkageError`, so a platform that renames it degrades the feature and
nothing fails.

Some of these workarounds live in the `CodeBridge.internal.backend` module, which a build with
`-PinternalApi=false` leaves out so the plugin passes the Plugin Verifier's internal API check. Those
entries say so, and say what such a build loses.

- [Drawing](#drawing)
    - [Declarative inlays cannot be coloured (worked around)](#declarative-inlays-cannot-be-coloured-worked-around)
    - [Declarative inlays only hear a Ctrl+click](#declarative-inlays-only-hear-a-ctrlclick)
- [Redrawing after an answer from outside the IDE](#redrawing-after-an-answer-from-outside-the-ide)
    - [Passes skip a file whose PSI has not changed](#passes-skip-a-file-whose-psi-has-not-changed)
    - [A daemon restart always waits the auto-reparse delay](#a-daemon-restart-always-waits-the-auto-reparse-delay)
- [Features registered up front](#features-registered-up-front)
    - [Intentions are one class each](#intentions-are-one-class-each)
    - [Code vision loses `singleEntryPerLine` in split mode](#code-vision-loses-singleentryperline-in-split-mode)
    - [File types cannot be added at runtime](#file-types-cannot-be-added-at-runtime)
    - [A type detected from content outlives its detector (worked around)](#a-type-detected-from-content-outlives-its-detector-worked-around)
    - [Status bar widgets are registered up front](#status-bar-widgets-are-registered-up-front)
    - [Status bar widgets only go on the right](#status-bar-widgets-only-go-on-the-right)
- [Synchronous APIs](#synchronous-apis)
    - [The Project view asks for children and decorations synchronously](#the-project-view-asks-for-children-and-decorations-synchronously)
    - [Nothing says what is on screen](#nothing-says-what-is-on-screen)
- [Split mode](#split-mode)
    - [No public way to act as the frontend client](#no-public-way-to-act-as-the-frontend-client)
    - [A split-mode lens click finds its entry by the line's first range](#a-split-mode-lens-click-finds-its-entry-by-the-lines-first-range)
    - [Documentation links reach the backend only as
      `psi_element://`](#documentation-links-reach-the-backend-only-as-psi_element)
    - [Frontend-friendly completion items get no backend step](#frontend-friendly-completion-items-get-no-backend-step)
    - [The frontend guesses Enter without the file's language](#the-frontend-guesses-enter-without-the-files-language)
    - [No public way to forward a backend port to the client](#no-public-way-to-forward-a-backend-port-to-the-client)
- [Editing](#editing)
    - [TextMate files never split brackets on Enter](#textmate-files-never-split-brackets-on-enter)
- [Settings and UI](#settings-and-ui)
    - [The Plugins page cannot be extended](#the-plugins-page-cannot-be-extended)
    - [A stripe button keeps the title its tool window was registered with](#a-stripe-button-keeps-the-title-its-tool-window-was-registered-with)
- [Internal extension points](#internal-extension-points)
    - [Sticky lines from a breadcrumbs collector](#sticky-lines-from-a-breadcrumbs-collector)
    - [Frontend-friendly insert handlers](#frontend-friendly-insert-handlers)
    - [Select Opened File for a file outside the project](#select-opened-file-for-a-file-outside-the-project)
    - [Plugin suggestions for a file something else supports](#plugin-suggestions-for-a-file-something-else-supports)
- [Minor](#minor)
    - [No project-scoped document listener](#no-project-scoped-document-listener)
    - [Why an inline completion was hidden is internal (worked around)](#why-an-inline-completion-was-hidden-is-internal-worked-around)
    - [Some public signatures still take obsolete types](#some-public-signatures-still-take-obsolete-types)
    - [Out-of-process JCEF calls only deprecated resource handler methods](#out-of-process-jcef-calls-only-deprecated-resource-handler-methods)

## Drawing

### Declarative inlays cannot be coloured (worked around)

**What VS Code offers.** A text editor decoration can attach text before or after a range (`before` / `after` with
`contentText`), styled with CSS: `color`, `backgroundColor`, `fontStyle`,
`fontWeight`, `textDecoration`, `border`, `margin`. Each colour can be a CSS colour or a theme
colour, and can differ between light and dark themes.

**Who notices.** Error Lens shows each diagnostic's message at the end of its line, coloured to
match its severity. GitLens' current-line blame is a grey `after` text, and its blame column a
`before` text of fixed width on a background. Drawn as declarative inlays, all of them would take the
IDE's inlay-hint colour, and Error Lens' messages could no longer be told apart by colour.

**What the platform offers.** Declarative inlay hints (`com.intellij.codeInsight.hints.declarative`)
are the kind the platform forwards to a split-mode frontend. Their styling is fixed:

- `PresentationTreeBuilder.text(text, actionData)` takes only the text.
- `HintFormat` chooses from `HintColorKind.Default`, `Parameter` and `TextWithoutBackground`, plus
  two font sizes and two margin/padding options. Each kind's colours come from the colour scheme (Editor | Color
  Scheme | Language Defaults | Inline hints), so they apply to every hint at once
  and not to one hint's text.

Inlays with a custom `EditorCustomElementRenderer` on the editor's `InlayModel` can be painted any
way the renderer likes, but one added on a backend editor does not reach a split-mode frontend.

**What CodeBridge does.** It does not use the platform's forwarding for this text. The backend sends
each decoration's ranges and resolved text styles to the frontend over CodeBridge's own RPC
(`CodeBridgeEditorDecorations.published`). The frontend then adds custom-rendered inlays to its own editors
(`CodeBridgeDecorations.DecorationText`), in the extension's colour, background, width and side margins. A
monolithic IDE does the same, its frontend half in the same process. Declarative inlays (`CodeBridgeDecorationInlaysProvider`,
`HintColorKind.TextWithoutBackground`) are left only for when no
frontend listens. Borders and font styles of the text are still not drawn.

**What would fix it.** A way to style a declarative inlay's text per hint. That could be a
`TextAttributesKey` or `TextAttributes` on `PresentationTreeBuilder.text`, or a foreground and
background colour, plus a font style, on `HintFormat`. It would have to be serialised to a
split-mode frontend, as the rest of the presentation tree already is. A colour that follows the
theme (a `JBColor`, or light and dark values) would cover VS Code's theme variants.

### Declarative inlays only hear a Ctrl+click

**What VS Code offers.** A code lens is a clickable line above the code ("Run | Debug",
"3 references"), and it runs its command on a plain click.

**What the platform offers.** A declarative inlay placed above the line could show it, and would
reach a split-mode frontend, but its `InlayActionHandler` is only handed a click with Ctrl held.

**What CodeBridge does.** Lenses are drawn as code vision instead (`CodeBridgeCodeLensProvider`), which is
clicked plainly and is what the IDE's own lenses and IntelliJ's LSP client use. Code vision has
its own split-mode problem, below.

**What would fix it.** A way for an inlay to ask for plain clicks, as an option on
`InlayActionData` or on the presentation.

## Redrawing after an answer from outside the IDE

Every language feature CodeBridge draws comes from another process. The platform's passes assume
their input is the PSI, and an answer from an extension changes no PSI.

### Passes skip a file whose PSI has not changed

**What VS Code offers.** A provider can say its results changed (`onDidChangeInlayHints`, a new
outline after a background analysis), and the editor asks again and redraws.

**What the platform offers.** Restarting the daemon for the file. But the sticky lines pass skips
a file whose PSI modification stamp has not moved since it last ran. Breadcrumbs decide whether a
file has a bar at all when it opens, which is before the extension host has the document. Two
other passes behave the same way, and have a public way round it, so they need no workaround:

- Declarative inlays (inlay hints and decoration text):
  `DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)`,
  for each editor of the file (`CodeBridgeInlayHintsProvider.recompute`).
- Folding: `CodeFoldingManager.scheduleAsyncFoldingUpdate`, for each editor of the file.

**What CodeBridge does.** The breadcrumbs bar and the sticky lines pass ask for the outline of the
text as it stands when they draw, as IntelliJ's LSP client does, so an edit needs nothing forced. An
outline nothing in the IDE asked for (a provider registering, a document reaching the host after its
editor's first passes ran, a retry after an answer of nothing) still needs internal API before the
restart: `StickyLinesCollector.forceCollectPass()` for sticky lines, and `reinitBreadcrumbsInAllEditors`
for the breadcrumbs bar on a file's first outline (`CodeBridgeInternalOutlineRedrawer`, called from
`HostedLanguageFeatures.redrawOutline`). Without
these, that outline is only drawn after the next edit, or, for breadcrumbs, after the file is
reopened. The LSP client reaches the bar the same way, through the internal
`BreadcrumbsXmlWrapper.FORCE_RELOAD_BREADCRUMBS` when a server starts, and does nothing for sticky
lines. From its bytecode, a server that starts after a file opens should leave that file with no
sticky lines until the next edit; this has not been reproduced.

**What would fix it.** A public counterpart to `scheduleRecompute` for sticky lines and
breadcrumbs. Both read the same `FileBreadcrumbsCollector`, but the only ways to say its data changed
(`FORCE_RELOAD_BREADCRUMBS`, and the handler `watchForChanges` is given) reach only the bar. One
public per-file call on the collector that refreshes both would cover it. More generally, one
public call that says "an external source changed what this file's passes should draw", scoped to a
file, so each pass does not need its own.

### A daemon restart always waits the auto-reparse delay

**What VS Code offers.** Diagnostics an extension pushes appear as soon as they arrive.

**What the platform offers.** `DaemonCodeAnalyzer.restart` does not run a pass. It schedules one
for now plus the auto-reparse delay (300ms by default), which exists to avoid highlighting between
keystrokes. A diagnostic push arrives after the user has stopped typing, so the delay is pure
latency: it was the whole of CodeBridge's share of how late errors appeared.

**What CodeBridge does.** It restarts inside the internal
`DaemonCodeAnalyzerSettings.forceUseZeroAutoReparseDelayIn` (`CodeBridgeInternalDaemonRestarter`, in
`CodeBridge.internal.backend`). Without it, an extension's answer is drawn after the auto-reparse delay.
Bursts of answers are coalesced into one restart first, so this does not cost the IDE extra passes.

**What would fix it.** A public restart that runs now, for callers whose change did not come from
typing.

## Features registered up front

VS Code's providers come and go at runtime, and their results are lists of any length. Several
IntelliJ extension points are registered in `plugin.xml`, one class per entry.

### Intentions are one class each

**What VS Code offers.** A code action provider answers with as many actions as apply at the caret.

**What the platform offers.** Each intention is a class registered in the descriptor, and the
platform asks each one whether it is available.

**What CodeBridge does.** It registers 20 slots (`CodeBridgeCodeAction0`..`19`), and each takes the action
at its index in the current answer (`CodeBridgeCodeActions.kt`). IntelliJ's own LSP client does the same.
Actions beyond the twentieth are not shown.

**What would fix it.** A dynamic intention source: an extension point that returns a list of
intention actions for an editor and offset, computed once per position.

### Code vision loses `singleEntryPerLine` in split mode

**What VS Code offers.** Several lenses on one line, side by side ("Run | Debug").

**What the platform offers.** A `CodeVisionProvider` that can draw several entries per line. In split
mode, the frontend stands in for a backend provider with one of its own, and that one does not carry
`singleEntryPerLine` over. So the default applies -- one entry per line, the last -- and
"Run | Debug" came out as "Debug". This looks like a bug rather than a missing API.

**What CodeBridge does.** It registers 8 providers chained after one another, the nth drawing the
nth lens on a line, sharing one settings group (`CodeBridgeCodeLensProvider.kt`). A ninth lens on a line is
not drawn.

**What would fix it.** The frontend's stand-in provider taking `singleEntryPerLine` (and the
provider's other settings) from the backend provider it stands in for.

### File types cannot be added at runtime

**What VS Code offers.** An extension's `contributes.languages` makes its files a language as soon
as it is installed, and stops when it is removed.

**What the platform offers.** File types registered in a plugin descriptor, or associations the
user saves in File Types settings. Without a file type, a file has no PSI and nothing can
annotate it, so a server can report problems while the editor stays blank.

**What CodeBridge does.** It answers through a `FileTypeOverrider` (`CodeBridgeLanguageFileTypeOverrider`),
an interface in the platform's `impl` package, and then calls `makeFileTypesChange`. Writing
associations into the user's settings would outlive the extension that justified them. The cost is
that the platform cannot find these types by name, so nothing can be read back from its caches, and
the overrider answers afresh every time.

**What would fix it.** A public way to register and unregister a file type at runtime, by
extension pattern, that the registry treats like a descriptor-registered one.

### A type detected from content outlives its detector (worked around)

**What VS Code offers.** Disabling or removing an extension takes its grammar off every file it
coloured.

**What the platform offers.** `FileTypeDetectionService` keeps a custom type a content detector
returned in the file's user data, and `clearCaches()` -- run by every file-type change -- clears the
detection flags beside it but not that. A file detected while a detector would claim it keeps that
type once the detector no longer does. TextMate's `TextMateFileDetector` is one: a file it detected
stays `textmate`, highlighted without a grammar, after the grammar is unloaded. And
`FileTypeManager.isFileOfType` runs content detection without asking the special file types first,
so a lookup for some other type is enough to put the answer there.

**What CodeBridge does.** It never lets TextMate hold a grammar for a file its overrider has let go.
`CodeBridgeLanguageFileTypes` changes the claims and reloads the TextMate bundles in one write
action and file-type change, from the same list of extensions, so detection never runs for a claimed
file while its grammar is loaded. A grammar an extension contributes without a language is not
covered: nothing claims those files, and the platform's cache can still keep them `textmate`.

**What would fix it.** Versioning the detected type the way the persisted flags already are, with
the file-type change count, so that a file-type change invalidates it.

### Status bar widgets are registered up front

**What VS Code offers.** `window.createStatusBarItem` adds an item whenever an extension likes, and
each is its own entry in the status bar, highlighted on its own when the mouse is over it.
rust-analyzer adds one, GitLens several.

**What the platform offers.** A widget exists because a `StatusBarWidgetFactory` is registered in
the descriptor, one widget per factory and project; `StatusBarWidgetsManager.updateWidget` ignores a
factory that is not. Adding one at runtime is `StatusBar.addWidget`, whose public overloads are
deprecated in favour of factories and whose `Disposable` overloads are `@ApiStatus.Internal`, or
registering a factory with `ExtensionPoint.registerExtension`, which is `@TestOnly`. The status bar
draws hover and press on the widget as a whole: it looks one level down (`getVisibleChildAt`), and
`WidgetEffectBoundsProvider`, which would let a widget say which part is under the mouse, is internal.

**What CodeBridge does.** One widget holds every item (`CodeBridgeStatusBarWidget`), so the platform would
highlight all of them as one button. The widget's panel is disabled, which the status bar takes as
"no effect", and each clickable item draws the hover and press itself with the status bar's colours,
insets and corner radius. That depends on the status bar continuing to skip disabled widgets, and on
the drawing staying close to `WidgetEffectRenderer`'s. A fixed number of factories, each showing the
nth item, would use only public API but cap how many items show.

**What would fix it.** A public way to add and remove a status bar widget at runtime, with an order
and a `Disposable`, or a public `WidgetEffectBoundsProvider` so one widget can highlight the part of
itself under the mouse.

### Status bar widgets only go on the right

**What VS Code offers.** A status bar item is aligned left or right (`StatusBarAlignment`), and the
two sides are apart: workspace-wide state on the left, things about the current file on the right.

**What the platform offers.** `StatusBarWidgetFactory` has no position, and `StatusBarWidgetsManager`
puts every widget on the right. `IdeStatusBarImpl.addWidgetToLeft` exists but is `internal`, and only
the old UI's tool-window button uses the left. `IdeStatusBarImpl.setCentralWidget` is on the
implementation class, `@ApiStatus.Experimental`, and the navigation bar's when it is in the status
bar. `StatusBar.setInfo`, the one public thing on the left, is a line of text that cannot be clicked.

**What CodeBridge does.** Both sides go in its one right-hand widget, the left-aligned items first,
each side by priority (`CodeBridgeStatusBarWidget`).

**What would fix it.** A position on `StatusBarWidgetFactory` (or on the widget), left or right, with
the left side drawing the same hover effect as the right.

## Synchronous APIs

### The Project view asks for children and decorations synchronously

**What VS Code offers.** Tree views (`TreeDataProvider.getChildren`) and file decorations (`FileDecorationProvider`)
answer asynchronously, and the explorer waits for them.

**What the platform offers.** The Project view asks a node for its children, and a
`ProjectViewNodeDecorator` for a node's decoration, on the spot, and an extension in another
process cannot answer in time.

**What CodeBridge does.** A level not fetched yet shows as loading, is fetched, and the view is
redrawn once it arrives (`ExplorerViews`). Decorations are drawn with what is known, a batch of the
nodes just drawn is asked about, and the view is redrawn when an answer decorates anything (`HostedDecorations`).

**What would fix it.** Asynchronous children and decorations for Project view nodes, as the
platform's async tree models already allow elsewhere.

### Nothing says what is on screen

**What VS Code offers.** Code lenses are resolved (`resolveCodeLens`) as they scroll into view, so a
provider only computes the commands of lenses someone can see.

**What the platform offers.** Code vision and inlays are collected for the whole file, and nothing
tells a provider which entries are visible.

**What CodeBridge does.** It resolves every lens without a command straight away
(`HostedLanguageFeatures.refreshCodeLenses`). A server that resolves lazily therefore does all of
its work up front.

**What would fix it.** A visible-range listener for code vision entries, or a public way to ask an
editor's visible range from the backend in split mode.

## Split mode

### No public way to act as the frontend client

**What VS Code offers.** `window.showTextDocument`, and anything else that shows UI, lands in the
window the user is looking at.

**What the platform offers.** In split mode, editors, tool windows and focus are per client. Work
done from an extension-host thread has no `ClientId`, and lands in a backend editor nobody sees.
Finding the connected client (`ClientSessionsManager.getProjectSessions(project,
ClientKind.CONTROLLER)`) and acting as it (`ClientId.withExplicitClientId`) is unstable API.

**What CodeBridge does.** It routes everything that shows UI through `CodeBridgeTextEditors.asClient`,
using that unstable API.

**What would fix it.** A public way for backend code started outside any UI action to say "show
this to the user who controls this project", or to get that user's client id.

### A split-mode lens click finds its entry by the line's first range

**What VS Code offers.** Lenses on one line each with their own range. GitLens starts its two at
columns 0 and 1 to order them.

**What the platform offers.** In split mode, a click reaches the backend (`BackendCodeVisionHost.findClickedLens`) as
the clicked entry's provider id and the range of the
line's *first* lens, whoever drew it. The backend looks only among entries of exactly that range. A
lens whose range differs from the first one on its line is drawn but cannot be clicked: "Cannot find
clicked lens" is logged and nothing runs. In a monolith the click carries the entry itself.

It is a bug, and its cause is in open-source code vision: `EditorCodeVisionContext` anchors each
line's lenses at the first one's range, and `RangeCodeVisionModel.handleLensClick` passes that range
for every entry on the line, which the split frontend forwards.

**What CodeBridge does.** Every entry it draws on a line spans the range of the line's first lens, so
its own lenses can all be clicked. A line that also has another provider's lens, such as IntelliJ's
Java lenses over a whole class or method, is left to the platform fix: there CodeBridge's lenses
are drawn but cannot be clicked in split mode. (It used to adopt the other provider's range, read
from the internal code vision context; that was dropped in favour of fixing the platform.)

**What would fix it.** `RangeCodeVisionModel` passing each entry's own range when it is clicked, which
the frontend then forwards unchanged; or, on the backend, matching on the provider id and the line.

### Documentation links reach the backend only as `psi_element://`

**What VS Code offers.** A hover can contain `command:` links that run an extension command when
clicked, if the extension trusted them.

**What the platform offers.** A split-mode frontend forwards only `psi_element://` links from Quick
Documentation to the backend, with the prefix stripped. Any other link goes straight to the
browser. A `DocumentationLinkHandler`'s content updater is never run in split mode either. Only
`resolveLink` results that are targets come back.

**What CodeBridge does.** It writes hover command links as `psi_element://command:...`, and
`CodeBridgeHoverLinkHandler` runs the command and resolves the link to the same hover (`HoverMarkdown.kt`).

**What would fix it.** Forwarding links of any scheme a backend `DocumentationLinkHandler` claims, and
running content updaters for them.

### Frontend-friendly completion items get no backend step

**What VS Code offers.** A completion item can carry a command that runs once the item is inserted.

**What the platform offers.** A `FrontendFriendlyInsertHandler` runs on the client with the insertion
itself, which is what makes the caret land in the right place without a round trip. But for such an
item the backend runs nothing at all: no insert handler, and no hook saying the item was chosen.

**What CodeBridge does.** The client's handler asks the backend to run the command itself (`CodeBridgeCommandInsertHandler` →
`CodeBridgeApi.runCompletionCommand`).

**What would fix it.** A backend notification that a frontend-friendly item was inserted, with the
item, after the client has inserted it.

### The frontend guesses Enter without the file's language

**What VS Code offers.** Enter is applied once, with the language configuration's brackets,
indentation rules and `onEnterRules`, so the closing bracket of `{|}` goes straight to its own line.

**What the platform offers.** In split mode the frontend applies Enter on its own, at once, and the
backend's result replaces it when it arrives. `ThinClientEnterHandlerDelegate` decides how by the
file's language on the frontend: a `ThinClientLanguage` file gets a bare line break with no indent
(`DefaultSkipIndent`), and any other language runs the whole Enter pipeline locally, with whatever
delegates the frontend has. The extension's grammar and language configuration are backend-only --
syntax colours reach the client as markup -- so whichever path is taken, the guess can differ from
the backend's answer, and the closing bracket is drawn in one place and then moves. Which path a
claimed file takes on the frontend has not been checked.

**What CodeBridge does.** Nothing. If the frontend reads the file as `textmate`, registering
`CodeBridgeTextMateEnterBetweenBraces` there too would make the guess match. If it reads it as
`ThinClientLanguage`, the frontend would need the language configuration sent to it.

**What would fix it.** A public way for a backend language to say Enter is not to be predicted on
the frontend, or for the frontend to receive the backend's indent and Enter rules for a file it
cannot read.

### No public way to forward a backend port to the client

**What VS Code offers.** `env.asExternalUri(http://localhost:N/...)` gives back an address the
user's browser can reach. In a remote window, VS Code tunnels the port from the remote machine to
the local one first (`MainThreadTunnelService`), and answers with the local address.

**Who notices.** Extensions whose OAuth sign-in waits on a loopback server they start themselves,
rather than a `vscode:` callback, and that open the provider's page with something other than the
platform's browser launcher. The provider redirects the browser to `localhost:N` on the user's
machine, while in remote split mode the server listens on the backend's.

**What the platform offers.** No API. But `BrowserUtil.browse` on a split-mode backend forwards
ports itself: given a URL naming `127.0.0.1:N` anywhere in it -- including a `redirect_uri` in the
query -- the client starts listening on a free local port, forwards it to `N` on the backend, and
opens the URL with the address rewritten (seen in 263: `Started port forwarding session: LISTENING -
127.0.0.1:60403 -> 63344`). That is undocumented behaviour, and it has two costs: it assumes every
such port is the backend's, and it asks "Remote host wants to open the following URL" without
telling the caller whether the user agreed. The only forwarding API is the terminal plugin's
(`TerminalPortForwardingApi`), internal and tied to terminal sessions.

**What CodeBridge does.** Pages are opened by the client itself (`CodeBridgeClientRequests`), so the
extension learns whether they were opened -- except a page naming a port on the backend's machine,
which goes to `BrowserUtil.browse` for its forwarding, and is answered "opened" before the user has (`HostedClient`).
`$asExternalUri` still returns the URI unchanged (`HostedWindow`), so an extension
that hands the address to the provider some other way is not helped. The editor's own `vscodium:`
callbacks are served on the client's built-in web server instead, which needs no forwarding (`CodeBridgeAppLinks`,
`HostedUrls`).

**What would fix it.** A public backend API to forward a backend port to the controller client and
get back the client-side address, as VS Code's tunnel service does -- which `$asExternalUri` could
answer with -- and a way to learn whether a browse request was allowed.

## Editing

### TextMate files never split brackets on Enter

This one is a gap in the bundled TextMate plugin, not a missing API: it has a public workaround,
and the fix belongs in TextMate.

**What VS Code offers.** Enter between a pair from the language configuration's `brackets` puts the
caret on an indented line of its own and the closing bracket on the next.

**What the platform offers.** `EnterBetweenBracesFinalHandler` does this for every language, but
only when `EnterBetweenBracesDelegate.bracesAreInTheSameElement` says the two brackets are different
PSI elements -- the default compares `findElementAt` at both offsets, to leave brackets inside one
string literal alone. A TextMate file is a single leaf, so every pair counts as one element, and
the TextMate plugin registers no delegate of its own. `{|}` became an indented `|}` on one line, in
every TextMate language, not only those from VS Code extensions.

**What CodeBridge does.** It registers `CodeBridgeTextMateEnterBetweenBraces` for the `textmate` language,
which answers from the brackets the language configuration declares at the caret's scope
(`TextMateEditorUtils.findRightHighlightingPair`). It is in a backend-only module, so in split mode
the frontend still guesses without it (see [above](#the-frontend-guesses-enter-without-the-files-language)).

**What would fix it.** The TextMate plugin registering an `enterBetweenBracesDelegate` for
`textmate` that checks pairs against the scope's highlighting pairs instead of PSI elements. It
loads on the frontend too, so that would also cover a split frontend that reads the file as
`textmate`.

## Settings and UI

### The Plugins page cannot be extended

**What VS Code offers.** Not applicable: this is about where CodeBridge's own UI can go.

**What the platform offers.** `PluginManagerConfigurable` is final and internal. Every
customisation extension point around it (`pluginsViewCustomizer`, `pluginManagerCustomizer`,
`pluginInstallationCustomization`) is `@ApiStatus.Internal`, and none of them adds a tab. The page's
components (`PluginsTab`, `PluginDetailsPageComponent`) are internal too, and bound to the IDE's own
plugin model.

**What CodeBridge does.** VS Code Extensions is a sibling settings page directly below Plugins, placed
with `groupWeight` (`VsCodeExtensionsConfigurable`), and its marketplace UI is rebuilt from public
components with the same `Plugins.*` theme keys, so it restyles with the real page (`MarketplacePanel`).

**What would fix it.** A public extension point for a tab on the Plugins page, or public versions of
the list and details components.

### A stripe button keeps the title its tool window was registered with

**What VS Code offers.** A view container's `title`, shown on its activity bar entry and read out by
screen readers.

**What the platform offers.** The only public way to register a tool window at runtime is the
deprecated `registerToolWindow(id, canCloseContent, anchor, disposable, canWorkInDumbMode, secondary)`,
which takes no title, so the window starts with its id as its stripe title. The overload that takes one,
`registerToolWindow(RegisterToolWindowTask)`, is `@ApiStatus.OverrideOnly`, and so is the inline
`registerToolWindow(id) { ... }` builder's call to it. Setting `stripeTitle` afterwards replaces the
window's title supplier, but in the new UI the `SquareStripeButton` was built with a `Presentation` over
the supplier the window had then: the button's tooltip follows the new title, while its text -- its
accessible name -- stays the id. `setShowStripeButton`, which could have the button made again, is
ignored in the new UI.

**What CodeBridge does.** Registers each container's window as `CodeBridge.<container id>` and sets its
title straight after (`CodeBridgeTreeViewsActivity`). The tooltip shows the container's title; a screen reader,
and the UI driver, read `CodeBridge.<container id>`. The split-mode UI tests find the buttons by that
name. Using the title as the id instead would name the button right, at the cost of windows whose saved
layout and shortcuts move whenever an extension renames its container or the IDE's language changes.

**What would fix it.** A stripe button whose presentation follows the tool window's current stripe
title, as its tooltip already does; or a public, non-override-only way to register a tool window with a
title.

## Internal extension points

Extension points that do what CodeBridge needs and are meant for plugins to implement, but are
marked `@ApiStatus.Internal`. Each has no public counterpart; the fix in every case is to make it
public, or experimental.

### Sticky lines from a breadcrumbs collector

**What VS Code offers.** Sticky scroll and breadcrumbs drawn from a provider's document symbols.

**What the platform offers.** A public `FileBreadcrumbsCollector` for the bar, whose
`computeStickyLineInfos` is also what the sticky lines pass asks -- but that method, the
`StickyLineInfo` it returns, and `requiresProvider` (whether the bar needs a `BreadcrumbsProvider`)
are internal.

**What CodeBridge does.** Overrides all three (`CodeBridgeBreadcrumbsCollector`). Without
`computeStickyLineInfos`, a file an extension claims has no sticky lines; without `requiresProvider`,
it has no breadcrumbs bar. The collector is in `CodeBridge.internal.backend`, with the redraw described
[above](#passes-skip-a-file-whose-psi-has-not-changed), so a build without that module has neither
for these files. The Structure view is unaffected.

**What would fix it.** Making those members of `FileBreadcrumbsCollector` public, since the class
already is and a collector for a file without PSI structure needs them.

### Frontend-friendly insert handlers

**What VS Code offers.** Completion inserts locally, with the caret placed at once.

**What the platform offers.** `FrontendFriendlyInsertHandler` and its
`completion.frontendFriendlyInsertHandler` extension point, which run an insert handler on a split
frontend. Internal, with a `TODO IJPL-207762 mark experimental` in its source.

**What CodeBridge does.** Implements it for its insert handlers (`CodeBridgeInsertHandler` and three
others). Without it, every insertion waits on a round trip to the backend.

**What would fix it.** IJPL-207762: the interface marked experimental instead of internal.

### Select Opened File for a file outside the project

**What VS Code offers.** Revealing a file in the explorer wherever a tree view places it.

**What the platform offers.** The Project view refuses to select a file that is neither in the
project nor in a library before asking any node, unless a `CanBeSelectedInProjectPaneProvider`
accepts it. That extension point is internal. A public `selectInTarget` adds a separate target to
Select In, and does not make Select Opened File work.

**What CodeBridge does.** Accepts the files its Explorer views stand for (`ExplorerViewsSelectable`),
such as a `jdt:` class in a library jar.

**What would fix it.** Making `CanBeSelectedInProjectPaneProvider` public.

### Plugin suggestions for a file something else supports

**What VS Code offers.** Not applicable: this is about the IDE suggesting a plugin for a file an
extension already handles.

**What the platform offers.** `PluginAdvertiserSuppressor`, documented as being for files "handled by
user-configured tooling (e.g., a manually configured LSP server)", which is CodeBridge's case. It is
internal.

**What CodeBridge does.** Suppresses the suggestion while an enabled extension supports the file
(`CodeBridgePluginAdvertiserSuppressor`, in `CodeBridge.internal.backend`). Without it, the IDE offers a plugin for a
language the user already has.

**What would fix it.** Making `PluginAdvertiserSuppressor` public.

## Minor

### No project-scoped document listener

**What the platform offers.** Document change listeners are application-wide (`EditorFactory.eventMulticaster`), as is
`FileDocumentManagerListener` for saves.

**What CodeBridge does.** Every edit and save in every open project reaches each project's
listener, and is dropped unless that project mirrored the file (`HostedLanguageServers`). This costs a
map lookup per event and is not a functional problem.

**What would fix it.** A project-scoped document listener, or a filter on the multicaster.

### Why an inline completion was hidden is internal (worked around)

**What VS Code offers.** An inline completion item's `command`, run after the item is accepted.

**What the platform offers.** The `Hide` event carries a public `finishType`, but its type,
`InlineCompletionUsageTracker.ShownEvents.FinishType`, is an internal enum from the usage
statistics, nested in a class that is also deprecated. In split mode the hide the client forwards
is the backend's only sign that a suggestion was accepted. `InlineCompletionEventAdapter` cannot be
implemented without overriding its internal, non-extendable `onSuggestionInitialized` either.

**What CodeBridge does.** Implements `InlineCompletionEventListener` directly and compares the
finish type's name with `SELECTED` through `Enum` (`CodeBridgeInlineCompletionLists`), so the bytecode
never names the internal enum. A renamed constant would stop accepted items' commands running.

**What would fix it.** A public finish type on `Hide`, or an "accepted" event the backend hears in
split mode.

### Some public signatures still take obsolete types

**What the platform offers.** `com.intellij.util.Consumer`, `com.intellij.util.Function` and
`org.jetbrains.concurrency.Promise` are `@ApiStatus.Obsolete`, in favour of `java.util.function` and
coroutines, yet the only way to implement some features still takes or returns them:
`XDebuggerEvaluator.getExpressionInfoAtOffsetAsync` returns a `Promise` (its synchronous sibling runs
in a read action, which cannot wait for an extension), `LineMarkerInfo` takes its tooltip as a
`com.intellij.util.Function`, and `HighlightUsagesHandlerBase.selectTargets` and
`PomDeclarationSearcher.findDeclarationsAt` hand over a `com.intellij.util.Consumer`. A list popup
with its own renderer, row count and a movable window has only `IPopupChooserBuilder`, whose
`setItemChosenCallback` and `setNamerForFiltering` take `com.intellij.util.Consumer` and `Function`;
`createListPopup` with a `ListPopupStep` sets neither the row count nor movability without the
`ListPopupImpl` implementation class.

**What CodeBridge does.** Uses those types where the signature requires them and nowhere else (`CodeBridgeEvaluator`,
`CodeBridgeTestLineMarkers`, `CodeBridgeHighlightUsagesHandlerFactory`, `CodeBridgeNavigation`, and the single-choice
quick pick in `CodeBridgeQuickInputs`). Simpler list popups are `ListPopupStep`s.

**What would fix it.** A suspending `getExpressionInfoAtOffset`, and overloads taking the
`java.util.function` types, `IPopupChooserBuilder`'s among them.

### Out-of-process JCEF calls only deprecated resource handler methods

**What the platform offers.** `CefResourceHandler.processRequest` and `readResponse` are deprecated in
favour of `open` and `read`. The in-process browser calls the new pair, but the out-of-process one,
which a split-mode frontend uses, calls only the deprecated pair
(`com.jetbrains.cef.remote.ClientHandlersImpl`). A handler with only `open` and `read` fails every
request there as an unreachable site.

**What CodeBridge does.** Its webview resource handler (`WebviewBrowser.Served`) overrides both
pairs, and `open` and `read` delegate to the deprecated ones.

**What would fix it.** Out-of-process JCEF forwarding `open` and `read`.
