# Architecture and development

CodeBridge supplies workbench-side services for a real VS Code extension host. Extension code
owns language-client startup, commands and debug configuration providers. IntelliJ supplies the
editor and debugger UI through the bridges described in [Runtime and LSP](runtime-and-lsp.md)
and the [protocol reference](extension-host-protocol.md).

## Three plugins

CodeBridge is published as three plugins, of which a user only ever chooses one of the last two;
the runtime comes with it:

- **CodeBridge Runtime** (`me.helvert.codebridge.runtime`, `src/runtime`) runs extensions: the
  pinned extension host, shipped per platform, and everything that draws what extensions do. It runs
  the extensions its sources give it -- the user's store, and whatever the `extensionSource`
  extension point adds -- and those extensions install themselves (CodeLLDB's platform package). It
  reads nothing of VS Code's own: no `.vscode/` folder, no `.code-workspace` file, no copy of the
  user's VS Code settings.
- **CodeBridge Bundled Extensions** (`me.helvert.codebridge.bundled`, `src/bundled`) is what a plugin made from
  a VS Code extension depends on. It declares `bundledExtension` and hands what plugins name there to
  the runtime as an `extensionSource`; nothing else.
- **CodeBridge** (`me.helvert.codebridge`, the root project, `src/vscode`) depends on the runtime and
  adds what makes the IDE work like VS Code for someone who uses it: browsing and installing from
  Open VSX, the **VS Code Extensions** page, VSIX installation, and the project's VS Code workspace
  files.

Only the runtime holds the runtime: two plugins each carrying it would collide on its module names
and start two extension hosts per project, so both others depend on it instead.

A plugin made from a VS Code extension names the extension's unpacked folder inside its own
directory:

```xml
<depends>me.helvert.codebridge.bundled</depends>
<extensions defaultExtensionNs="me.helvert.codebridge">
    <bundledExtension path="extension"/>
</extensions>
```

Such an extension is listed with the user's own, ahead of an installed copy with the same
identifier, and comes and goes with its plugin: it cannot be installed, disabled or uninstalled on
its own, only disabled for one workspace. [`bundling-plugin`](../src/test-extensions/bundling-plugin/README.md)
is one.

CodeBridge's and CodeBridge Bundled Extensions' modules build on three of the runtime's -- `shared`, `backend` and
`frontend` -- which a content module of another plugin may only do by namespace: all three plugins
declare `<content namespace="codebridge">`, those three modules `visibility="internal"`, and the
others' descriptors name the namespace on each dependency on them. Without it the IDE drops every CodeBridge
module and still reports the plugin as loaded.

What lets CodeBridge add the workspace files without the runtime reading them on its own is one
extension point, `me.helvert.codebridge.vsCodeFiles`, which `workspace.backend` registers. Where
nothing does, the workspace is the project directory alone, `launch.json`, `tasks.json` and the
project's `settings.json` read as absent, and an extension sees settings as VS Code shows them in a
window with no folder open: a write with no target goes to the user's settings, and one naming the
workspace is refused. The `launch.json` and `tasks.json` run configuration types stay in the runtime,
because the debug sessions and tasks extensions start themselves run as them too.

## Module ownership

| Module                                                              | Responsibility                                                                                                                                                                                                                                                                                                                                                |
|---------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Root                                                                | CodeBridge: assemble the plugin, declare its IntelliJ dependency and the runtime it depends on, and include its content modules. Also holds `CodeBridge.jsonfiles`, a descriptor-only module typing `.code-workspace` files as JSON on every side of the IDE |
| `runtime`                                                           | CodeBridge Runtime: assemble that plugin from the runtime's content modules, and download, verify and repack the pinned extension host into its per-platform distributions |
| `bundled`, `bundled.backend`                                       | CodeBridge Bundled Extensions: assemble that plugin, and the `bundledExtension` point it reads into an `extensionSource` for the runtime |
| `frontend`                                                          | What the client draws: the **Tools → CodeBridge** settings page, messages and quick picks, the status bar widget, contributed views' tool windows, debugger call-stack menus, snippet tab-stop keys, editor open requests, and opening the user's settings file                                                                                         |
| `backend`                                                           | Runtime status, extension storage, network operations, project hosts, launch/task execution, and the IntelliJ debugger and language-feature integrations                                                                                                                                                                                                      |
| `textmate.backend`                                                  | Optional backend TextMate bundle provider and application-wide grammar reloads; requires the TextMate plugin and its `intellij.textmate` content module                                                                                                                                                                                                       |
| `terminal.frontend`                                                 | Extensions' shell terminals as tabs of the Terminal tool window, made on the frontend. Optional, since it requires the Terminal plugin                                                                                                                                                                                                                        |
| `json.backend`                                                      | `launch.json`, `tasks.json`, `settings.json` and contributed JSON schemas, JSONC compliance, launch-entry and `${...}` variable completion. Part of CodeBridge, so a plugin bundling an extension on the runtime alone has no contributed JSON schemas. Optional, since it requires the JSON plugin                                                                                                                                                                       |
| `marketplace.shared`, `marketplace.backend`, `marketplace.frontend` | Part of CodeBridge: browsing and installing from Open VSX, VSIX installation, enabling, disabling and uninstalling extensions, and the **VS Code Extensions** settings page; RPC contracts, the registry client on the backend, and the UI on the frontend                                                                                                                                                                                            |
| `workspace.shared`, `workspace.backend`, `workspace.frontend`       | The project's VS Code workspace files, part of CodeBridge: registering `vsCodeFiles`, choosing a `.code-workspace` file and indexing its folders, `files.exclude`, and opening `launch.json`, `tasks.json` and `settings.json` |
| `testrunner.backend`                                                | The `vscode.tests` API drawn with IntelliJ's test runner: gutter markers, the test run configuration and its console. Optional, since the test runner is the bundled `intellij.testRunner.plugin`; the tests themselves are kept by `backend`                                                                                                                 |
| `webview.frontend`                                                  | Extensions' webviews drawn in the IDE's browser: panels as editor tabs, webview views as tool window tabs. Optional, since the browser is the bundled Web Browser (JCEF) plugin; the webviews themselves are kept by `backend`                                                                                                                                |
| `internal.backend`                                                  | What only the platform's internal API allows: breadcrumbs and sticky lines from an extension's outline, redrawing them, drawing diagnostics without the auto-reparse delay, and no plugin suggestions for files an extension supports. A build with `-PinternalApi=false` leaves it out; [platform limitations](platform-limitations.md) says what that loses |
| `protocol`                                                          | VS Code's extension host protocol: the envelope (frames, RPC messages, the rpcId table) and the payload shapes those messages carry. No IntelliJ, no VS Code installation, no extension manifest                                                                                                                                                              |
| `shared`                                                            | RPC interfaces, DTOs, serializers and models; currently also backend-used process/pipe transports, the extension host session, the host's document mirror and archive utilities                                                                                                                                                                               |
| `ui-test`                                                           | Not part of the plugin. An opt-in suite that launches a real backend and a real JetBrains Client and asks the client what it drew -- the one question the other suites cannot answer                                                                                                                                                                          |

The split is not absolute: `shared` is still broader than contracts, and
`src/runtime/backend/src/main/kotlin/me/helvert/codebridge/launch/CodeBridgeDebugRunConfiguration.kt` still contains a small Swing
`SettingsEditor`.

`protocol` is the first piece taken out of it, in two packages. `protocol` itself is the envelope
-- bytes in, frames and RPC messages out -- and `protocol.payload` is what those messages carry:
the JSON shapes exchanged with the host, most of which have no published description and were read
off the runtime bundle. Both are facts about one build of VS Code, and both are tested by replaying
traffic captured from a real host, with no runtime on disk.

The rule that keeps the boundary honest is the dependency list: kotlinx-serialization and the JDK,
nothing else. Anything that needs the IntelliJ platform, a VS Code installation or an extension
manifest belongs in `shared` or above -- which is why `ExtensionHostSession` (a host and a state
machine), `ExtensionHostInitData` (installations and manifests) and `HostedLanguageFeatures` (an
IntelliJ service) all stayed where they are and call into `protocol` instead.

In Remote Development, filesystem paths, environment variables and child processes belong to the
backend machine. Frontend Settings and marketplace operations use serializable RPC contracts for
those services. Local VSIX selection transfers the client file to the backend. In a monolithic IDE
the modules share a process. The `ui-test` suite drives a real client against a real backend; its
coverage is described in [Tests and fixtures](../src/test-extensions/README.md#split-mode).

## Design decisions

- **Keep the extension host in the execution path.** Language and debugger extensions can contain
  startup, configuration and command logic beyond LSP/DAP. Connecting directly to a server would
  not execute that logic. Language support preserves extension ownership of the server.
- **Ship a pinned VSCodium remote-extension-host runtime, and run only that.** It provides the
  host and its Node executable without a desktop renderer. The plugin is published as one
  distribution per OS and architecture, each carrying only its own platform's runtime, so nothing is
  fetched at run time. Substituting another VS Code is not offered: rpcIds drift between builds, and
  a mismatch misroutes calls silently. Current pins and supported platforms are documented in the
  [runtime reference](runtime-and-lsp.md#runtime-selection).
- **Treat runtime compatibility explicitly.** RPC identifiers depend on the running bundle.
  The build accepts only the pinned runtime archives, by SHA-256, and identifiers are extracted
  from the running bundle.
  Invalid explicit runtime selections report an error instead of silently selecting another.
- **Use Open VSX for extension discovery.** Prefer a matching platform package when available;
  native debugger/server binaries must match the backend machine. Runtime engine requirements are
  displayed but not enforced.
- **Keep VS Code extensions on their own Settings page.** The page is registered at the top level
  using `parentId="root"`. Adapted IntelliJ UI components live in `marketplace.frontend`;
  their attribution is retained in [NOTICE.md](../NOTICE.md).

## Build and run

The root [build](../build.gradle.kts) and the [runtime's](../src/runtime/plugin/build.gradle.kts) target IntelliJ
`IU-263.7307.44` (2026.3 EAP); the wrapper pins the Gradle version.
Configure a compatible Gradle JVM through the IDE or `JAVA_HOME`, then run from the repository root:

```sh
./gradlew buildPlugin
```

In PowerShell use `.\gradlew.bat buildPlugin`. That builds CodeBridge,
`build/distributions/CodeBridge-<version>.zip`, one ZIP for every platform. The runtime it depends on
is built per platform by `:runtime:buildPluginVariants`, such as
`src/runtime/build/distributions/CodeBridgeRuntime-<version>-windows-x86_64.zip`; its plain
`:runtime:buildPlugin` ZIP carries no extension host and cannot start one. Install the runtime for
your OS and architecture first, then CodeBridge, both with **Settings → Plugins → ⚙ → Install Plugin
from Disk…**. `runIde` and the tests install both by themselves. An ordinary plugin build does not require npm
installation; the prototype scripts and demo fixture have separate dependencies described in the
[runtime and LSP reference](runtime-and-lsp.md). Test commands live in the
[testing guide](../src/test-extensions/README.md).

The shared [run configurations](../.idea/runConfigurations/) are **IntelliJ IDEA (Split Mode)**, **IntelliJ IDEA (
Monolith)**, **RustRover (Split Mode)** and **RustRover (Monolith)**.
Each *(Split Mode)* configuration is a compound of the backend and frontend Gradle tasks, kept under *&lt;IDE&gt; (Split
Mode) parts*, because it is the only way to attach the debugger to both processes:
`runIdeSplitMode` and a split-mode `runIde` leave the frontend undebuggable. The Gradle plugin only
makes `runIdeBackend` and `runIdeFrontend`, so the build registers `runRustRoverBackend` and
`runRustRoverFrontend` itself. Only one split-mode backend can run at a time, whichever IDE it is
of. On the command line, `runIde` runs split mode
by default and `-PsplitMode=false` runs a monolithic IDE. Inspect the backend `idea.log` for host and
endpoint failures.

CodeBridge's [plugin descriptor](../src/vscode/plugin/resources/META-INF/plugin.xml) declares ID
`me.helvert.codebridge`, display name **CodeBridge**, its dependency on the runtime and its content
modules; the [runtime's](../src/runtime/plugin/resources/META-INF/plugin.xml) declares
`me.helvert.codebridge.runtime`, **CodeBridge Runtime**, and the rest. Each module has a
`CodeBridge.<module>.xml` descriptor under its own `src/main/resources/`. The Gradle project name determines
the distribution filename. The icon lives beside the root descriptor in `META-INF/pluginIcon.svg`.
