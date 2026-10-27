# Contributing to CodeBridge

Thanks for helping. Bug reports, extension compatibility reports and pull requests are all welcome.

## Reporting a problem

Open an issue with the bug report template. The most useful reports name the IDE and its version,
the OS, whether the IDE runs in split mode (remote development) or as a single process, and the
extension and version involved. Logs help most:

- the IDE's `idea.log` (**Help → Show Log in …**); in split mode, the backend's log has the host
  and extension errors;
- the **CodeBridge Output** tool window, where extensions log;
- the extension host's own logs, described in [Runtime and LSP](docs/runtime-and-lsp.md).

An extension that doesn't work isn't necessarily a bug: check [Status](docs/status.md) first, since
some of the VS Code API isn't supported yet.

Report security problems privately instead; see [SECURITY.md](SECURITY.md).

## Before you start

For anything beyond a small fix, open an issue first so we can agree on the approach. CodeBridge's
[design philosophy](README.md#design-philosophy) rules some changes out:

- **Generic, not tailored.** Nothing is special-cased for one extension. A fix for one extension has
  to come from implementing the VS Code API it uses, so every extension using that API benefits.
- **IntelliJ features, not VS Code panels.** What an extension contributes maps onto the IDE's own
  features, icons, theme and shortcuts.
- **The `.vscode` folder stays as it is.** It is read and, where VS Code would write it, written,
  but never converted.

When the IntelliJ Platform can't show something an extension provides, record it in
[Platform limitations](docs/platform-limitations.md), written as a feature request for the platform.

## Building and running

You need JDK 25 or later. From the repository root:

```sh
./gradlew buildPlugin   # .\gradlew.bat on Windows
./gradlew runIde        # a sandbox IDE in split mode; -PsplitMode=false for a single process
```

The shared run configurations in `.idea/runConfigurations/` start IntelliJ IDEA or RustRover in
either mode, with the debugger attached to both split-mode processes.
[Architecture and development](docs/architecture.md) explains the modules and the build, and
[Runtime and LSP](docs/runtime-and-lsp.md) how the extension host is started and how to trace it.

## Tests

```sh
./gradlew freshTest      # every module's unit and integration tests
./gradlew :ui-test:test  # split-mode UI tests; close any sandbox IDE first
```

`freshTest` ignores the build cache, which can otherwise serve test classes compiled against older
sources. The [testing guide](src/test-extensions/README.md) covers the fixtures, the tests that need
the extension host, and running the UI tests headless on Linux or WSL.

A new feature comes with a UI test that exercises it in a real IDE.

## Code

- Kotlin, following the style of the surrounding code. Comments explain *why*, not *what*.
- Use coroutines rather than futures or callbacks.
- Don't use internal, override-only, obsolete or deprecated IntelliJ Platform API. Experimental API
  is fine. CI runs the Plugin Verifier, but it doesn't flag obsolete API, so check by hand.
- Update the documentation in `docs/` when behaviour changes, [Status](docs/status.md) especially.

[`AGENTS.md`](AGENTS.md) holds the same rules for coding agents.

## Commit messages

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
fix(lsp): show hover Markdown as rendered text

rust-analyzer sends MarkupContent with kind "markdown", which the hover
popup showed verbatim.

Refs #42
```

- The type is one of `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci` or `chore`.
- The scope is optional: the area the change is in, such as `lsp`, `webview`, `terminal`,
  `marketplace` or `ui-test`. Leave it out when a change spans several areas.
- The subject is imperative and lowercase, without a trailing period. Aim for 50 characters, at
  most 72, both counting the prefix. Say what changes, not how or why: leave class and file
  names out, and put "instead of ..." reasons in the body.
- The body, when there is one, explains why. Wrap it at 72 characters.
- Refer to an issue in a footer: `Refs #42` in commits, `Fixes #42` in the pull request.
- Mark a breaking change with `!` after the type or scope, such as `feat(api)!:`, and describe it
  in a `BREAKING CHANGE:` footer.

## Pull requests

Keep a pull request to one change, describe what it fixes or adds and how you tested it, and make
sure CI passes. Its title follows the same format as a commit subject.

By contributing, you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), like the rest of the project. If you add third-party code, icons or
other material, it must be under a licence compatible with Apache 2.0, keep its original copyright
notice, and get an entry in [NOTICE.md](NOTICE.md).
