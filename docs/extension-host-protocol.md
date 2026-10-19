# Extension-host protocol reference

This describes the subset implemented by CodeBridge's Kotlin host session. It is a maintenance
reference, not a guarantee of compatibility with arbitrary VS Code builds. Runtime selection and
the two LSP paths are documented in [Runtime and LSP](runtime-and-lsp.md); test prerequisites and
recorded results are in [Tests and fixtures](../src/test-extensions/README.md).

## Bootstrap and activation

[ExtensionHostLaunch.kt](../src/runtime/shared/src/main/kotlin/me/helvert/codebridge/exthost/ExtensionHostLaunch.kt) launches
`out/bootstrap-fork.js`. REH installations use their bundled Node executable; desktop installations
use Electron with `ELECTRON_RUN_AS_NODE=1`. The launch environment supplies:

| Variable | Value/purpose |
|---|---|
| `VSCODE_ESM_ENTRYPOINT` | `vs/workbench/api/node/extensionHostProcess` |
| `VSCODE_EXTHOST_IPC_HOOK` | Address of the pipe/socket on which Kotlin is already listening |
| `VSCODE_NLS_CONFIG` | Locale and the runtime's `out/nls.messages.json` path |
| `VSCODE_PARENT_PID` | Parent process to monitor |
| `VSCODE_HANDLES_UNCAUGHT_ERRORS` | `true` |

[NamedPipeServer.kt](../src/runtime/shared/src/main/kotlin/me/helvert/codebridge/exthost/NamedPipeServer.kt) supplies a Windows
named pipe or Unix-domain socket. The extension host connects to Kotlin. The session then follows
this sequence over the framed transport:

1. Host sends **Ready** (`0x02`).
2. Kotlin sends UTF-8 JSON initialization data: runtime identity, extension descriptions, storage
   and environment data. `consoleForward` must be present; see
   [ExtensionHostInitData.kt](../src/runtime/shared/src/main/kotlin/me/helvert/codebridge/exthost/ExtensionHostInitData.kt).
3. Host sends **Initialized** (`0x01`).
4. Kotlin calls `ExtHostConfiguration.$initializeConfiguration` and
   `ExtHostWorkspace.$initializeWorkspace`. Both must complete before the session reports ready
   for activation. Receiving the Initialized byte alone is insufficient.

`Terminate` is the third handshake value (`0x03`). These are single-byte payloads inside frames,
not frame types. Session closure currently destroys the process and closes its transport.

## Framing

[Frames.kt](../src/runtime/protocol/src/main/kotlin/me/helvert/codebridge/exthost/protocol/Frames.kt) encodes a 13-byte header.
Integers are
big-endian; lengths count bytes. Reads can contain partial frames or several frames together.

| Offset | Field | Width |
|---|---|---|
| 0 | Frame type | 1 byte |
| 1 | Frame ID | 4 bytes |
| 5 | Acknowledged frame ID | 4 bytes |
| 9 | Payload length | 4 bytes |
| 13 | Payload | Variable |

Outgoing regular-frame IDs start at 1 and increase without gaps. A gap can leave the host waiting
for an apparently lost message. Defined frame types are Regular (1), Control (2), Ack (3),
Disconnect (5), Replay Request (6), Pause (7), Resume (8), and Keep Alive (9).
`ExtensionHostSession` ignores keep-alive/resume frames; it does not implement the full upstream
persistent-protocol reconnection/replay machinery.

## RPC payloads and identifiers

[RpcMessages.kt](../src/runtime/protocol/src/main/kotlin/me/helvert/codebridge/exthost/protocol/RpcMessages.kt) implements JSON
requests and
replies carried in regular frames. Request type 1 is plain JSON; type 2 marks it cancellable.

| Offset | Request field |
|---|---|
| 0 | Type: uint8 |
| 1 | Request ID: uint32 |
| 5 | Actor `rpcId`: uint8 |
| 6 | UTF-8 method-name byte length `n`: uint8 |
| 7 | Method name: `n` bytes |
| `7+n` | Argument JSON byte length: uint32 |
| `11+n` | Arguments: UTF-8 JSON array |

An empty success reply (type 7) is only the type byte and request ID. JSON success (type 9) and
serialized error (type 11) add a four-byte JSON length and the UTF-8 JSON body. Omitting that length
corrupts the reply. Errors use the host's serialized error shape, including `$isError`, `name` and
`message`; individual bridge services encode their own protocol-specific error details.

Mixed requests (type 3, or 4 when cancellable) carry an argument count and then each argument
tagged as JSON (1), a binary buffer (2), JSON with nested buffers (3) or undefined (4); a buffer
keeps its position and stands as `null` in the JSON array. Binary replies (type 8) and JSON replies
with buffers (type 10) are read and written too, as are acknowledgments (5) and cancellation (6):
a cancel from the host cancels the handler's pending result.

[RpcIdTable.kt](../src/runtime/protocol/src/main/kotlin/me/helvert/codebridge/exthost/protocol/RpcIdTable.kt) extracts identifiers from
the
actual `extensionHostProcess.js` bundle. IDs are one-based and follow proxy-identifier creation
order across both directions. The factory is minified, so extraction identifies it by its calls,
then includes all of its identifier names in order. A property name can differ from its identifier
string: `MainThreadLanguageModelTools` uses `MainThreadChatSkills` in the pinned runtime.

A bundled `1.132.1` fallback table is used only when extraction fails. It is still accurate for the
pinned `1.135.06055` runtime -- the identifier list is unchanged between the two -- and a name it
does not hold fails loudly in `require` rather than routing to the wrong service. Extracting IDs does
not guarantee that method signatures remain compatible.

## Message payloads

Framing and rpcIds say which service is being called; they say nothing about what the call carries.
Those shapes live in
[protocol/payload](../src/runtime/protocol/src/main/kotlin/me/helvert/codebridge/exthost/protocol/payload), beside the rpcId
table and for the same reason: both are facts about one build of VS Code, and neither is published
anywhere.

| Shape                                                                                                                                                                                                         | Read and written by                                                                                                                                                             |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UriComponents`                                                                                                                                                                                               | Every resource on the protocol: `{scheme, authority, path, query, fragment}`, never a URI string                                                                                |
| `Position`, `Range`                                                                                                                                                                                           | VS Code's editor coordinates, **1-based** in both line and column -- not LSP's 0-based positions                                                                                |
| `Args`                                                                                                                                                                                                        | Positional argument lists, with one policy for an argument that is absent or the wrong type                                                                                     |
| `Diagnostics`, `Marker`                                                                                                                                                                                       | `MainThreadDiagnostics.$changeMany`, and `ExtHostDiagnostics.$acceptMarkersChange` for the problems IntelliJ itself finds                                                       |
| `Completions`, `CompletionItem`                                                                                                                                                                               | `$provideCompletionItems`, whose reply is keyed by single minified letters                                                                                                      |
| `LanguageFeatures`                                                                                                                                                                                            | Provider registrations, `$provideHover`/`$provideCompletionItems`/`$provideDefinition`/`$provideDocumentHighlights` requests, and their replies                                 |
| `SemanticTokens`                                                                                                                                                                                              | Semantic token registrations and legends, and the binary, 0-based, delta-encoded reply                                                                                          |
| `CodeActions`, `Formatting`                                                                                                                                                                                   | Code action and formatter registrations, their requests, and replies cached by list id until released                                                                           |
| `SignatureHelp`, `InlayHints`, `CodeLenses`, `FoldingRanges`, `DocumentSymbols`, `WorkspaceSymbols`, `SelectionRanges`, `DocumentLinks`, `DocumentColors`, `LinkedEditing`, `OnTypeFormatting`, `Hierarchies` | Each provider's registration, request and reply, and the release of replies or sessions the host caches                                                                         |
| `Markdown`                                                                                                                                                                                                    | `MarkdownString` values: their text, unescaped, and which command links are trusted                                                                                             |
| `TextEdit`, `WorkspaceEdit`, `FileOperations`                                                                                                                                                                 | Edits as `$tryApplyWorkspaceEdit`, code actions and formatters send them, file operations kept in order; and the will/did events for files the user creates, deletes or renames |
| `DocumentSelector`                                                                                                                                                                                            | Whether a provider's selector claims a file: language, scheme and glob                                                                                                          |
| `Documents`                                                                                                                                                                                                   | Document synchronization: the open delta, the change event, save, close and encoding changes; and `MainThreadDocuments`' open, create and save requests                         |
| `Encodings`, `Indentation`                                                                                                                                                                                    | VS Code's encoding ids, and the `tabSize` and `insertSpaces` an editor reports                                                                                                  |
| `OutputChannels`, `Progress`, `StatusBar`, `Messages`, `Configuration`                                                                                                                                        | The workbench services an extension reports its own state on, and its settings                                                                                                  |
| `Console`, `Loggers`, `Telemetry`, `LogLevel`                                                                                                                                                                 | What an extension logs, and the telemetry the host forwards                                                                                                                     |
| `DebugService`, `DebugAdapters`                                                                                                                                                                               | Debugger registrations, and the DAP relay described below                                                                                                                       |
| `TextEditors`, `EditorTabs`, `TextEditorDecorations`, `CssWidth`                                                                                                                                              | Editor edits, snippets, selections and reveal; decoration types and their ranges, with the CSS widths they size text by; the tab model sent to the host                         |
| `TreeViews`, `ViewsWelcome`, `WhenClause`, `Webviews`                                                                                                                                                         | Contributed views, their welcome content and `when` clauses, and webviews                                                                                                       |
| `QuickOpen`, `Dialogs`, `Decorations`, `Tasks`, `Terminals`, `Testing`, `WorkbenchCommands`                                                                                                                   | The calls of the remaining workbench services                                                                                                                                   |
| `DocumentContentProviders`                                                                                                                                                                                    | Text content providers, and opening a document under a provider's scheme                                                                                                        |
| `Authentication`, `SecretState`                                                                                                                                                                               | Authentication providers and sessions, and `context.secrets`                                                                                                                    |
| `Urls`, `Clipboard`                                                                                                                                                                                           | URI handlers and links back into the editor, and `env.clipboard`                                                                                                                |

Two of these are worth knowing about before changing anything:

**Resources.** `path` is VS Code's `/C:/x` spelling, which `java.net.URI` reads correctly only with
a scheme in front of it, while IntelliJ's own `VirtualFile.url` spells the same file `file://C:/x`
-- where the drive letter parses as the URI *authority* and the path comes out as `/x`. A resource
that makes that round trip badly is not rejected by anything: the host files the document under a
name the workbench never asks about again. `UriComponents.key` is the normalization everything
keyed by a URI goes through, lowercase Windows drive included.

**Completion items.** `{x: cacheId, a: defaultRange, b: items, c: isIncomplete}`, each item `{a:
label, b: kind, c: detail, d: documentation, e: sortText, f: filterText, h: insertText, i:
insertTextRules, j: range}`. Those single letters are what the minifier left behind, read off the
runtime bundle exactly as the rpcIds are. It is the most version-fragile shape here, which is why
it is pinned by tests that replay captured replies.

Arguments and results stay raw JSON text in `RpcMessage` itself. Decoding belongs to the payload
shape for the method being called, because every method has its own, and there is nothing useful to
say about them generically.

## Workbench handlers and DAP

[ExtensionHostSession.kt](../src/runtime/shared/src/main/kotlin/me/helvert/codebridge/exthost/ExtensionHostSession.kt) dispatches
incoming `MainThread*` calls to the configured handler through `WorkbenchDispatcher`: one thread
that handles calls from the host and completes the replies to calls made to it, in the order they
arrived, so a registration sent before an activation reply is handled before that reply completes.
A handler must not block that thread waiting for another call to the host, whose reply needs the
same thread; it answers through `handleAsync` instead.
Startup handlers need correctly shaped results, such as an object for
`MainThreadWindow.$getInitialState` and an array for `MainThreadChatSkills.$getTools`.
Returning an empty success to an unimplemented service may permit activation; it does not supply
that service's editor or workbench functionality.

The existing DAP seam already forwards complete debugger messages through host RPC.
[ExtensionHostDapTransport.kt](../src/runtime/backend/src/main/kotlin/me/helvert/codebridge/debug/ExtensionHostDapTransport.kt)
uses `ExtHostDebugService.$startDASession(handle, sessionDto)` to start an adapter,
`$sendDAMessage(handle, message)` to send DAP messages, and `$stopDASession(handle)` to stop it.
`MainThreadDebugService.$acceptDAMessage` carries replies/events back; `$acceptDAError` and
`$acceptDAExit` report failure/exit. `DebugAdapters` builds and reads those six, and deliberately
leaves a DAP message as raw text: it is the debugger's own protocol, and this side only relays it. The extension host owns executable, server and inline adapter
lifetimes. Debug configuration providers and adapter factories are resolved before this transport
is handed to IntelliJ's DAP runner.

Language features use the same shape, without a forwarding actor: providers are registered to the
plugin over `MainThreadLanguageFeatures`, diagnostics are pushed over `MainThreadDiagnostics`, and
the plugin calls `ExtHostLanguageFeatures.$provideHover` back. See
[the workbench protocol](runtime-and-lsp.md#the-workbench-protocol).
