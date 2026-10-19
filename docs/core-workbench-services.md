# Core workbench services

The project extension host uses `HostedWorkbench` for basic workspace services. Language and debug
extensions use the same bridge, so a debug session keeps the project's storage and file services.
All paths refer to the backend machine in Remote Development.

## Implemented scope

| Service           | Behavior                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `workspace.fs`    | Local `file:` stat, directory listing, binary reads/writes, mkdir, copy, rename and deletion. Overwrite flags are honored; recursive operations do not traverse symbolic links. Errors use VS Code filesystem error names. Local provider capabilities are advertised to the host.                                                                                                                                                                    |
| Workspace search  | `findFiles` and existence searches across every workspace folder, or within a supplied relative-pattern root. Supports globstar, alternatives, wildcards, explicit excludes, boolean `files.exclude`, result limits and cancellation.                                                                                                                                                                                                                 |
| File watchers     | A native recursive workspace watcher supplies events even when the extension uses a string glob and sends no watch request. Explicit watchers also support external paths, exclusions, includes, correlation and event filters. New subdirectories are registered; unwatch and host shutdown release registrations.                                                                                                                                   |
| Extension storage | `globalState` and `workspaceState` survive host restarts. Writes replace state files atomically where supported. Global state is shared across backend project hosts and sends change notifications to initialized subscribers; workspace state is isolated by a hash of the normalized project path. Extension storage directories also have stable locations.                                                                                       |
| Configuration     | Extension-contributed defaults and saved `.vscode/settings.json` are sent to the host, including language overrides. Disk changes trigger configuration notifications after a short debounce for atomic saves. Invalid edits retain the last valid snapshot.                                                                                                                                                                                          |
| Extension logging | `console.*` calls the host forwards are rendered and written to the IDE log at the severity the console method implies; `console.error` is recorded as a warning, because `LOG.error` files an IDE error report. Logger registrations are recorded so a `$log` batch can name the extension it came from, and an extension's own log is shown as a tab of the **CodeBridge Output** tool window. Telemetry events are recorded by name at debug and dropped. |
| Commands          | Registration, unregistration, discovery and `onCommand:` activation are connected. Contributed commands receive positional arguments and return their results asynchronously. The debugger's validated `installFromVSIX` handling remains in its existing path.                                                                                                                                                                                       |

Persistent state is stored beneath the IntelliJ system directory at `codebridge/state`. Temporary
host copies and logs remain separate. Extension state is not Settings Sync: declared sync keys are
retained locally but are not sent to an account service.

The RPC codec understands mixed JSON/binary arguments and binary replies. Incoming requests
run on a separate ordered dispatcher. A pending command releases that dispatcher while awaiting
its host callback, allowing nested workbench requests and RPC replies to proceed. Cancellable file
searches stop when the host cancels their request.

The wire shapes were checked against the pinned runtime bundle and Microsoft's
[extension-host protocol](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/api/common/extHost.protocol.ts)
and [RPC implementation](https://github.com/microsoft/vscode/blob/main/src/vs/workbench/services/extensions/common/rpcProtocol.ts).

## Remaining limits

- Custom filesystem providers are read only to show their files, and are not workspace file
  systems; see [workbench coverage](workbench-coverage.md). Trash deletion is rejected. File
  operations are offered to extensions' will/did participants, but have no transactional rollback.
  Nested binary command arguments are rejected explicitly, without closing the host.
- Search does not implement text search, ignore-file parsing, sibling-dependent exclude clauses,
  full `findFiles2` semantics or traversal through symbolic-link directories.
- A chosen `.code-workspace` file's folders are the workspace's, and follow the file and the choice
  while the host runs.
  Save requests and proxy resolution remain unsupported. See
  [multi-root workspaces](workspace-configurations.md#multi-root-workspaces).
- Watcher overflow is logged, without a full reconciliation scan. Very large workspaces need
  appropriate `files.watcherExclude` settings to limit native registrations.
- Configuration updates write `.vscode/settings.json` (workspace and folder targets) or the user
  settings file (user and application targets), changing only that key. In a multi-root workspace
  the workspace target is the `.code-workspace` file's `settings` and the folder target the
  resource's folder's `.vscode/settings.json`. As in VS Code, unregistered keys,
  application/machine-scoped keys aimed at the workspace, window-scoped keys aimed at a folder, and
  files with unsaved editor changes are refused. Remote user settings and account settings are not
  implemented.
- Arbitrary built-in workbench/UI commands remain unsupported and use the existing logged no-value
  fallback. They are not advertised by command discovery. This is not a general command palette bridge.
- The host writes `file:` loggers itself with spdlog under the `logsLocation` it is given; an
  extension's own (non-hidden) log is followed from that file into a **CodeBridge Output** tab while the tab
  is showing. Log level changes are not pushed to the host. Telemetry is dropped rather than forwarded, and its event
  properties are not logged.
- Storage migration and account synchronization remain unsupported. The standalone debugger test
  harness still has its old minimal handlers; normal plugin debugging uses the project host.

## Validation

`BinaryRpcTest` checks the mixed-argument wire layout, binary replies and invalid buffer lengths.
`WorkbenchDispatcherTest` checks nested requests, registration/reply ordering, cancellation and
error isolation without requiring a native extension host.
`HostedServicesTest` exercises real temporary files, persistence across restarts/project boundaries,
command activation, search exclusions/cancellation, configuration reload/error handling and the
severities, naming and discarding that extension logging applies.
`LoggingTest` checks the console, telemetry and logger payloads in the spellings the pinned runtime
sends, including the sentence the host substitutes for arguments it cannot serialize and the log
level table that must not be confused with spdlog's.
`HostedFileWatchersTest` exercises real native create/change/delete events, new directories,
external subscriptions, filtering/disposal and settings saved by file replacement.

These run without an extension host. The services are exercised against a live one by the native
host tests and the split-mode suite; see [Tests and fixtures](../src/test-extensions/README.md).
