# Third-party notices

CodeBridge
Copyright 2026 Bart van Helvert

Licensed under the Apache License 2.0; see [LICENSE](LICENSE). The components below are included
under their own licences.

## Extension host runtime (VSCodium, VS Code, Node.js)

Each platform's plugin distribution carries VSCodium's remote extension host under `runtime/`: the
unmodified `vscodium-reh-<platform>-1.143.17183` package from https://github.com/VSCodium/vscodium,
plus the built-in extension manifests (`package.json` only) of the same build's web package. It is
downloaded at build time and not part of this repository.

- **VSCodium**, MIT, `Copyright (c) 2018-present The VSCodium contributors`.
- **Visual Studio Code -- Open Source** 1.143.1, which VSCodium builds, MIT,
  `Copyright (c) 2015 - present Microsoft Corporation`, with the third-party components it lists in
  its `ThirdPartyNotices.txt`.
- **Node.js** 24.22.1, the `node` executable, MIT, `Copyright Node.js contributors`, with the
  components it bundles listed in its `LICENSE`.

The archive carries none of these licences, so the build adds them under `runtime/licenses/`:
`vscodium-LICENSE.txt`, `vscode-LICENSE.txt`, `vscode-ThirdPartyNotices.txt` and `node-LICENSE.txt`.
The packages under `runtime/node_modules/` and the built-in extensions under `runtime/extensions/`
carry their own licence files.

## CodeLLDB (vadimcn.vscode-lldb)

`src/test-extensions/exthost/codelldb-1.12.3.vsix` is the unmodified universal package of CodeLLDB 1.12.3,
redistributed here so the extension store's installation test can run offline, and
`src/runtime/shared/src/test/resources/codelldb-package.json` is the `package.json` inside it, which the manifest
parser's tests read. Neither is packaged in the plugin and no code from them is used.

The archive declares MIT in its manifest but contains no licence file, so the required notice is
reproduced here.

```
Copyright (c) 2016 Vadim Chugunov

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Source: https://github.com/vadimcn/codelldb --- licence text from that repository's `LICENSE`.

## Visual Studio Code: webview styles and setting descriptions

Two files the plugin ships contain material from Visual Studio Code -- Open Source 1.143.1
(https://github.com/microsoft/vscode):

- `src/runtime/webview/frontend/src/main/resources/webview/default.css` is the default stylesheet VS Code puts in every
  webview, from `src/vs/workbench/contrib/webview/browser/pre/index.html`, without its
  `@layer vscode-default` wrapper, `overscroll-behavior-x` and find-highlight rules.
- `src/runtime/shared/src/main/resources/codebridge/workbench-settings.json` describes the workbench settings CodeBridge
  supports, with descriptions taken from VS Code's own and amended where CodeBridge behaves differently.

```
MIT License

Copyright (c) 2015 - present Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Language client test fixture (vscode-languageclient and dependencies)

`src/test-extensions/exthost/lsp-bundled-extension/dist/extension.js` is a bundle that includes
`vscode-languageclient` 9.0.1, `vscode-jsonrpc` 8.2.0, `vscode-languageserver-protocol` 3.17.5 and
`vscode-languageserver-types` 3.17.5 (MIT, `Copyright (c) Microsoft Corporation`), `semver` 7.8.5 and
`minimatch` 5.1.9 (ISC, `Copyright (c) Isaac Z. Schlueter and Contributors`), and `brace-expansion`
2.1.4 and `balanced-match` 1.0.2 (MIT, `Copyright (c) 2013 Julian Gruber`). It is a test fixture and
not packaged in the plugin. Their licence texts are in `THIRD-PARTY-NOTICES.txt` beside it.

## IntelliJ Platform (intellij-community)

CodeBridge includes source code copied and adapted from the IntelliJ Platform, so that its
extension pages match the layout and metrics of the IDE's own Plugins page exactly rather than
approximating them.

```
Copyright 2000-2025 JetBrains s.r.o. and contributors.
Use of this source code is governed by the Apache 2.0 license.
```

Full licence text: https://www.apache.org/licenses/LICENSE-2.0

Source: https://github.com/JetBrains/intellij-community —
`platform/platform-impl/src/com/intellij/ide/plugins/newui/` and its `buttons/` subdirectory

### Files copied

Under `src/vscode/marketplace/frontend/src/main/java/me/helvert/codebridge/extensions/ui/`, copied apart from the package
declaration and the removals noted:

| File | Change |
|---|---|
| `LinkComponent.java` | package only |
| `PluginSearchTextField.java` | package only |
| `TagComponent.java` | dropped the EAP/paid/trial/ultimate tag styling, which is specific to JetBrains Marketplace licensing and pulls in `IdeBundle`, `ApplicationInfoImpl` and `PluginAdvertiserService` |
| `BaselinePanel.java` | dropped `setListeners` and `setProgressComponent`, the only members that referenced `EventHandler` and `ListPluginComponent` |

Under `src/vscode/marketplace/frontend/src/main/kotlin/me/helvert/codebridge/extensions/ui/`:

| File                      | Change                                                                                                                                                                                                                                                                             |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PluginRow.kt`            | ported from `ListPluginComponent.BaselineLayout`, extracted from its enclosing class, with the plugin model, checkbox, progress indicator and `OnOffButton` handling removed. Rows use compact padding, and the action controls are centred beside the full text block.            |
| `TextHorizontalLayout.kt` | ported to Kotlin and built on the platform's public `ListLayout` instead of extending the plugin page's deprecated `HorizontalLayout`.                                                                                                                                             |
| `EnableCheckBox.kt`       | ported from `ListPluginComponent.createEnableDisableButton`, as a named class taking the initial state and a callback instead of the plugin model.                                                                                                                                 |
| `OptionButton.kt`         | ported from `buttons/OptionButton`, with the action and options set after construction only.                                                                                                                                                                                       |

`RowStackLayout.kt` is modelled on `PluginListLayout` rather than copied. The source headers of all
of these retain the JetBrains copyright and Apache 2.0 notices.

## Codicons (@vscode/codicons)

`src/runtime/shared/src/main/resources/codicons/` holds VS Code's codicons, drawn for any codicon the IDE has no
icon of its own for. They are the SVGs of the npm package `@vscode/codicons` 0.0.46-24,
`Copyright (c) Microsoft Corporation`, licensed under the Creative Commons Attribution 4.0
International licence: https://creativecommons.org/licenses/by/4.0/legalcode. Each is recoloured
from `currentColor` to the IDE's light and dark icon colours; `codicons/LICENSE.txt` beside them
carries this notice into the plugin.

Source: https://github.com/microsoft/vscode-codicons

## File icons (JetBrains icons, seti-ui, VS Code theme-seti)

`src/runtime/shared/src/main/resources/fileicons/` holds the file icons. `fileicons/LICENSE.txt` beside the icons
lists where each one came from and carries the notices below into the plugin. Icons the IDE ships
itself are not copied: the mapping names them and they are taken from the running IDE.

**Which file gets which icon** is VS Code's default file icon theme, Seti:
`mapping.properties` is derived from `extensions/theme-seti/icons/vs-seti-icon-theme.json` at VS Code
commit `3f07e1aba32acacb8b08ae91bfdc954b580ad1fd`, `Copyright (c) Microsoft Corporation`, licensed under
the MIT License: https://github.com/microsoft/vscode/blob/main/LICENSE.txt

**`fileicons/jetbrains/`** holds icons by JetBrains s.r.o. from its open-source plugins, from
https://intellij-icons.jetbrains.design/, each carrying JetBrains' Apache 2.0 notice in the file
(https://www.apache.org/licenses/LICENSE-2.0). They are bundled unmodified. Icons JetBrains publishes
without a licence -- those of its other products and of Toolbox -- are not bundled.

**`fileicons/seti/`** holds the rest. Each is a glyph of VS Code's `extensions/theme-seti/icons/seti.woff`,
written out as an SVG path in the theme's light and dark colours. The glyphs are seti-ui's, at commit
`2d6c5e68b4ded73c92dac291845ee44e1182d511`, the one that VS Code commit's `cgmanifest.json` records:

```
Copyright (c) 2014 Jesse Weed

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

Source: https://github.com/jesseweed/seti-ui --- licence text from that repository's `LICENSE.md`.

Many icons are simplified renderings of programming languages' and tools' logos, which are trademarks
of their owners. They are used only to show which language a file is written in, and imply no
endorsement.

## Brotli decoder (org.brotli:dec)

The plugin bundles Google's Java Brotli decoder, `org.brotli:dec` 0.1.2, its classes unmodified inside
`CodeBridge.shared.jar`, to unpack the WOFF2 icon fonts extensions contribute.

```
Copyright (c) 2009, 2010, 2013-2016 by the Brotli Authors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

Source: https://github.com/google/brotli

## JetBrains Markdown (org.jetbrains:markdown-jvm)

The plugin bundles JetBrains' Markdown parser, `org.jetbrains:markdown-jvm` 0.7.16, as a jar in its
`lib/` directory, unmodified, to render hovers. By JetBrains s.r.o., licensed under the Apache License
2.0: https://www.apache.org/licenses/LICENSE-2.0 (also in this distribution's `LICENSE`).

Source: https://github.com/JetBrains/markdown
