# Activation failure probe

An extension whose `activate()` throws as soon as a `.codebridgefailing` file opens. VS Code's
release build shows no notification for this. It marks the extension in the Extensions view and
puts the error under its runtime status, and CodeBridge does the same on the VS Code Extensions
page.

Build with `gradlew buildActivationFailureFixture`. The VSIX is written to
`build/fixtures/codebridge-activation-failure-probe.vsix` and is not shipped in the plugin.
`SplitModeActivationFailureTest` installs it, opens such a file, and reads the failure off the
page in the client.
