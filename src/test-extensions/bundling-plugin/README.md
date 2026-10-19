# Bundling plugin

An IntelliJ plugin made of a VS Code extension and nothing else, the way a vendor would publish
their extension to the JetBrains Marketplace: a descriptor naming CodeBridge Bundled Extensions as a dependency
and `me.helvert.codebridge.bundledExtension`, and the unpacked extension beside it. It has no classes.

The extension is [`exthost/lsp-bundled-extension`](../exthost/lsp-bundled-extension/README.md),
so it brings a real activation, a child language server and diagnostics, rather than contributions
alone. `gradlew buildBundlingPluginFixture` lays it out in `build/fixtures/bundling-plugin/`:

```text
lib/bundling-probe.jar   META-INF/plugin.xml
extension/               the probe, as its VSIX unpacks
```

`SplitModeBundledExtensionTest` installs it in the backend beside CodeBridge Runtime and Bundled Extensions
alone, with an empty extension store, and checks that the client gets the extension's diagnostics,
that disabling it for the workspace still works, and that the project's `.vscode/tasks.json` is not read.
