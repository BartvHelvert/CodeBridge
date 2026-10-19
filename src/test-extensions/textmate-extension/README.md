# TextMate split-mode probe

A declarative extension with no JavaScript entry point or server. It claims `.codebridgegrammar` and
colours `PROBE` as a keyword and double-quoted text as a string. Its language configuration
declares brackets and indentation rules, for the editing tests. Example:

```text
PROBE ordinary "string"
```

Build with `gradlew buildTextMateFixture`. The VSIX is written to
`build/fixtures/codebridge-textmate-probe.vsix` and is not shipped in the plugin.

`SplitModeTextMateTest` installs it only in the backend extension store. It checks the syntax
colours forwarded to the client's editor, then disables, enables and uninstalls the extension
through the backend service and checks the same open editor. The client has no local bundle.

Run the opt-in experiment in PowerShell:

```powershell
.\gradlew.bat :ui-test:test --tests '*SplitModeTextMateTest'
```

The `textmate.backend` tests cover provider discovery, syntax reload and editor change notifications,
including a replacement that changes only token colours while preserving its version and file
mapping. They run with `freshTest`.
