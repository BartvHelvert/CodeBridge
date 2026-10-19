# lsp-socket-extension

The same controlled server as `lsp-bundled-extension`, reached over a socket instead of stdio.

`server.cjs` differs from the bundled fixture's only in its identity string and the socket plumbing;
`extension.cjs` differs only in passing `TransportKind.socket`. That near-duplication is the point:
when the only variable is the transport, a test passing against both has shown the transport does
not matter.

**Used by** `LanguageFeaturesIntegrationTest`, alongside the bundled fixture.

**What it is evidence for.** The workbench protocol is transport-independent, and this fixture is
what makes that a test rather than a claim. The removed stdio proxy could
only ever reach a server on child-process stdio; this fixture is what demonstrates the replacement
has no such limit. Delete it and that claim goes back to being untested.

Its older comment describes it as "a server the proxy path cannot reach", which was about the stdio
proxy, since removed. The boundary it pinned is gone; the transport evidence is what remains.

## Prerequisite

Unlike the bundled fixture, this one keeps its dependencies, because only its transport is under
test:

```
npm ci --ignore-scripts --prefix src/test-extensions/exthost/lsp-socket-extension
```

## Overlap

This fixture and `lsp-bundled-extension` could reasonably become one that picks its transport from
the language that activated it, saving ~2.4M of `node_modules` and this npm step. It would need the
committed bundle rebuilt. Not done.
