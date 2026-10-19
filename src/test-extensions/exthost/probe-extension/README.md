# probe-extension

The smallest thing that proves a host booted.

Activates on `"*"`, calls the real `vscode` API, and logs a marker the test greps for. No language
features, no dependencies, two files.

**Used by** `ExtensionHostSessionTest` (locate an installation, boot, activate) and
`RemoteExtensionHostTest` (the same, against an unpacked remote-extension-host package on its own
Node -- which is what the plugin actually ships).

**Why it stays separate from the LSP fixtures.** Those activate on a language and start a client and
a server, so a failure in one has several possible causes. This one has almost none: if it fails, the
host did not come up. That is worth keeping distinct from "the host came up and the language client
then failed", which is a different bug with a different fix.

Its `"*"` activation is deliberate and is also its limit -- eager activation never calls `$activate`,
which is exactly why the `onLanguage:` identifier bug could not have been caught here. Do not extend this fixture to
cover language features; that is what `lsp-bundled-extension` is.
