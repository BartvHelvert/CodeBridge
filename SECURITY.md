# Security policy

## Reporting a vulnerability

Please don't report security problems in public issues. Report them privately through
[GitHub's private vulnerability reporting](https://github.com/BartvHelvert/CodeBridge/security/advisories/new),
with a description of the problem, the steps to reproduce it and the CodeBridge version or commit.

You'll get an acknowledgement within a week. Once a fix is ready, the advisory is published with
credit to you, unless you'd rather stay anonymous.

## Supported versions

CodeBridge has no releases yet. Fixes go into the `master` branch.

## Scope

CodeBridge runs VS Code extensions in an extension host with the same access to your machine as
the IDE, just as VS Code does. An extension doing something harmful is not a CodeBridge
vulnerability in itself. These are:

- an extension, a web page or a webview gaining access CodeBridge should not give it, such as
  escaping a webview into the IDE, or reaching another project's extension host;
- something other than the IDE using the local endpoints CodeBridge opens, such as the URI handler
  that passes `vscodium://` links back to the IDE, or the extension host's connection;
- installing an extension from Open VSX or a `.vsix` file writing outside its own folder, or
  installing something other than what was chosen;
- the bundled extension host runtime being swapped or changed without the build or the plugin
  noticing.

Vulnerabilities in VS Code's extension host or Node.js themselves belong with
[VSCodium](https://github.com/VSCodium/vscodium/security), [VS Code](https://github.com/microsoft/vscode/security)
or [Node.js](https://nodejs.org/en/security); if CodeBridge ships an affected version, report that
here too so it gets updated.
