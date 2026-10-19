<p align="center">
  <img src="docs/images/banner.svg" width="480" alt="CodeBridge: VS Code extensions in IntelliJ IDEs">
</p>

CodeBridge lets you use VS Code extensions in IntelliJ-based IDEs. You browse and install them from
[Open VSX](https://open-vsx.org) on a **VS Code Extensions** page in Settings, next to the IDE's own
Plugins page and laid out the same way. A `.vsix` file works too. Your project's `.vscode` folder works too:
`launch.json`
entries become run configurations, `tasks.json` tasks can run before them, and extensions use the
settings in `settings.json`, as they do in VS Code.

Extensions run unmodified, because CodeBridge runs them in the real VS Code extension host, which
comes with the plugin. You don't need VS Code or Node.js installed. What extensions provide shows
up as ordinary IntelliJ features: errors in the editor, completion, intentions, actions like Go to
Declaration, Find Usages, Rename and Reformat Code, the debugger and more.

> [!NOTE]
> CodeBridge is in active development. Many extensions work well, but some of what VS Code offers
> extensions is not available, so installing an extension does not mean all of its features will
> work. See [Status](docs/status.md) for the details.

## Features

- **Extensions:** search Open VSX, see an extension's details, and install, update, turn off or
  uninstall extensions on a **VS Code Extensions** page in Settings, laid out like the Plugins page.
  You can also install a `.vsix` file.
- **Editing:** errors and warnings, semantic colouring, syntax highlighting, hover docs,
  completion, inline completions as ghost text, signature help, inlay hints, code lenses, folding,
  Extend Selection and document links.
- **Navigation:** Go to Declaration, Find Usages, usage highlighting, Go to Class/Symbol, the
  Structure view and breadcrumbs.
- **Refactoring:** Rename, the extension's quick fixes and refactorings as intentions, and Reformat
  Code with the extension's formatter.
- **Debugging:** debug with an extension's debugger in IntelliJ's debugger, using the entries in
  your `launch.json`.
- **Workspace:** your `launch.json`, `tasks.json` and `settings.json`, including multi-root
  `.code-workspace` files. When an extension changes a setting, it is saved to `settings.json`.
- **Extension UI:** extension views as tool windows, webviews, status bar items, notifications,
  quick picks, progress, terminals and test runners.

Notebooks, source control, comments, custom editors and extensions' chat and AI features are not
supported. The full breakdown is in [Status](docs/status.md).

CodeBridge also works with remote development (split mode): the UI stays on your machine and
extensions run where the project lives. Installing extensions, settings and the core editor
features have been tested this way; some longer workflows haven't been checked end to end.

CodeBridge runs on Windows, macOS and Linux. Windows on ARM is not supported.

## Design philosophy

CodeBridge doesn't rebuild VS Code inside IntelliJ. An extension should feel as if it had been
written for IntelliJ.

- **IntelliJ features, not VS Code panels.** Quick fixes are intentions, extension views are tool
  windows and tests run in IntelliJ's test runner, with the IDE's own icons, theme and shortcuts.
- **Generic, not tailored.** Nothing is special-cased for one extension; what works for one works
  for any extension that uses the same API.
- **Your `.vscode` folder stays as it is.** It isn't converted, so you and teammates on VS Code
  share the same configuration.

## Installing

CodeBridge has no release yet, so you build it yourself. It is two plugins: CodeBridge Runtime,
which runs extensions, and CodeBridge, which adds the marketplace and your `.vscode` folder. With
JDK 25 or later, run `./gradlew :runtime:buildPluginVariants buildPlugin` (`.\gradlew.bat` in
PowerShell) from the repository root. Install the runtime's ZIP for your OS and architecture from
`src/runtime/build/distributions/`, then CodeBridge's from `build/distributions/`, with **Settings →
Plugins → ⚙ → Install Plugin from Disk…**. The runtime's plain `buildPlugin` ZIP carries no
extension host, so it can't run extensions. [Architecture and development](docs/architecture.md#build-and-run)
has the details.

## Using it

- **Find and install extensions** in **Settings → VS Code Extensions**. Search Open VSX, open an
  extension to see its details and click **Install**, or **Update** when a newer version is out.
  Your installed extensions are listed on the same page, where you can turn them off (everywhere
  or just in this project) or uninstall them.
- **Install a `.vsix` file** with **Install Extension from Disk…** in the gear menu on the same page.
- **Language support** turns on when you open a file an enabled extension handles.
- **Debugging:** every entry in your `launch.json` appears in the run widget, in a **launch.json**
  folder; pick one and click Run or Debug.
- **Extension logs** appear in the **CodeBridge Output** tool window.

## Documentation

- [Status](docs/status.md): what works today, feature by feature.
- [Workspace configurations](docs/workspace-configurations.md): how `launch.json`, `tasks.json` and
  `settings.json` are used.

### Development

- [Architecture and development](docs/architecture.md): modules, design decisions and building the plugin from source.
- [Runtime and LSP](docs/runtime-and-lsp.md): how the pieces talk, and troubleshooting.
- [Core workbench services](docs/core-workbench-services.md): implemented APIs, persistence and remaining limits.
- [Workbench coverage](docs/workbench-coverage.md): which VS Code services are answered and which are not.
- [Platform limitations](docs/platform-limitations.md): what the IntelliJ platform can't show.
- [Extension-host protocol](docs/extension-host-protocol.md): how CodeBridge talks to the extension host.
- [Tests and fixtures](src/test-extensions/README.md): running tests and recorded results.

## Contributing

Bug reports and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md). Report security
issues privately as described in [SECURITY.md](SECURITY.md).

## License

CodeBridge is licensed under the [Apache License 2.0](LICENSE). It includes third-party code and
icons under their own licences, listed in [NOTICE.md](NOTICE.md).

CodeBridge is an independent project, not affiliated with or endorsed by Microsoft or JetBrains.
Visual Studio Code and VS Code are trademarks of Microsoft Corporation; IntelliJ is a trademark of
JetBrains s.r.o.
