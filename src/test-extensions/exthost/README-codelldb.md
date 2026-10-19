# codelldb-1.12.3.vsix

A real, unmodified, third-party extension. The only fixture we did not write.

The universal `vadimcn.vscode-lldb` 1.12.3 package from
[Open VSX](https://open-vsx.org/api/vadimcn/vscode-lldb/1.12.3/file/vadimcn.vscode-lldb-1.12.3.vsix).
92K, because the universal package carries no native binaries.

**Used by**

| Test                        | What it establishes                                                                                                                                     |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ExtensionStoreTest`        | Installs offline, with no activation and no network                                                                                                     |
| `RealExtensionSurveyTest`   | Activates it and records which workbench services it touches -- this survey fed the service table in [Status](../../../docs/status.md)                  |
| `DebugAdapterDiscoveryTest` | Produces a debug adapter descriptor, which the manifest alone cannot: codelldb builds it in code and asks the workbench to install its platform package |

`VsixManifestParserTest` parses a checked-in copy of its `package.json` separately, without the
archive.

**Why a fixture we wrote cannot replace it.** The point is that we did not write it. Our own fixtures
test what we expect an extension to do; this one tests what an extension actually does, including the
parts we would not have thought to imitate -- an adapter built at runtime rather than declared, a
platform payload fetched on demand, and a manifest large enough (46K) to have real shape.

## Licence and redistribution

MIT, declared in the extension's own `package.json`. MIT permits redistribution, so keeping the
archive in this repository is allowed -- but it requires the copyright and permission notice to
travel with the copy, and **the VSIX itself ships no licence file** (its ten entries are the
manifest, `extension.js`, readme, changelog, a syntax file and three images). The notice is therefore
carried in the repository's [third-party notices](../../../NOTICE.md) instead.

Do not repackage or modify the archive. It is kept locally so the store installation test works
offline; the debug-adapter test may still download codelldb's native platform package at runtime.
