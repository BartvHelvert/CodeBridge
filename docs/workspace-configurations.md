# Workspace launch and task configurations

The plugin reads `.vscode/launch.json`, `.vscode/tasks.json` and `.vscode/settings.json` from the
IntelliJ project's base directory on the backend. JSONC comments and trailing commas are accepted by
the parsers.

## Multi-root workspaces

A `.code-workspace` file in the project's base directory makes the project VS Code's multi-root
workspace once you choose it, as opening that file in VS Code does; opening the project alone, like
opening a folder in VS Code, does not. When the project directory holds workspace files and you have
not decided, a notification on opening offers **Open Workspace** (one file) or **Select
Workspace…** (several), and **Don't Use**; ignored, it asks again next time. The **VS Code
Workspace** submenu, in the Tools menu and on the project's node in the Project view's context
menu, changes the choice later: **This Folder Only** or one of the files, the one in use ticked. In use, the project's
node in the Project view
says so (*VS Code workspace: name*, as VS Code's Explorer heads it "name (Workspace)"), and the
other workspace folders inside the project read *VS Code workspace folder*. The choice is kept
per project. Changing it restarts the extension host in the new workspace, as VS Code reloads
its window when it opens another folder or workspace. The chosen file's `folders` (relative or absolute `path`, or a
`file:` `uri`, with an optional
`name`) are the workspace folders extensions see; folders that do not exist are left out, and a file
that does not parse, or names no folder, leaves the project directory the workspace, with a warning.
Folders outside the project directory are indexed as well, without changing the project's own
model: each is listed under External Libraries as `name (VS Code workspace folder)`, so it can be
searched and navigated.

- **Settings**: the file's `settings` are the workspace's. Each folder's `.vscode/settings.json`
  applies to that folder's resources, and, as in VS Code, only for settings scoped `resource`,
  `language-overridable` or `machine-overridable`. An extension's write goes to the file, or with
  the folder target to the `.vscode/settings.json` of the folder holding the resource it names.
- **Launch configurations**: each folder's `.vscode/launch.json` and the file's `launch`, listed as
  `Name (folder)` and `Name (workspace)`. `${workspaceFolder}` is the entry's folder;
  `${workspaceFolder:name}` names any folder, which is how the file's own entries name one. A
  compound member's `folder` is read.
- **Tasks**: a folder's entry's `preLaunchTask` and `postDebugTask` are looked for in that folder's
  `.vscode/tasks.json` first, then in the file's `tasks`. A folder's task runs in that folder.
- **Search and watching** cover every folder, each with its own `files.exclude` and
  `files.watcherExclude`.
- **Editing**: the file is opened as JSON, with `folders`, `settings`, `launch` and `tasks` typed as
  their own files are. Its `launch` offers the debuggers' templates, and `${...}` completion works
  in its `launch` and `tasks`, reading their own `inputs`.

The folders follow the chosen file while the extension host runs, as in VS Code: editing
`folders`, or deleting the file, changes the folders extensions see (`onDidChangeWorkspaceFolders`), and the settings,
search, watching and launch configurations with
them.

## Editing the files

With the JSON plugin enabled, `launch.json`, `tasks.json` and `settings.json` get completion,
documentation and validation from JSON schemas built as VS Code builds them, from the enabled
extensions:

- **`.vscode/launch.json`**: a configuration's `type` offers every installed debugger that declares
  `configurationAttributes`. Once `type` and `request` are set, the configuration offers that
  debugger's attributes and the fields every configuration has (`name`, `preLaunchTask`,
  `presentation`, ...). Compounds and inputs are typed too. As in VS Code, a property the debugger
  does not declare is reported. Where a new configuration can go, completion offers every
  debugger's templates (its `configurationSnippets`, such as "CodeLLDB: Launch"). One inserts with
  its first placeholder selected, such as `<program>`. As in VS Code, Tab and Shift+Tab move between
  the placeholders, and Esc, Tab from the last one, or moving the caret out of the entry ends that.
  A placeholder written twice is visited once; the other copy does not follow what is typed. Extensions that complete in
  `launch.json` themselves are asked too: CodeLLDB's
  "CodeLLDB: Cargo ...", in a workspace with a `Cargo.toml`, asks which Cargo target to debug and
  writes its configuration. The file reaches them as `jsonc`, which is what the VS Code runtime's
  own built-in extensions call it, once the extension host is running or an installed extension
  would start it.
- **`.vscode/settings.json`** and the user settings file: every `contributes.configuration` setting,
  with its values and descriptions, and language overrides such as `"[rust]": {...}`, which offer
  the settings scoped `language-overridable`. The workspace file leaves out settings scoped to the
  `application` or the `machine`, which belong in user settings. Of VS Code's built-in settings,
  only those CodeBridge acts on are declared: `files.exclude`, `files.watcherExclude`,
  `files.autoSave`, `files.autoSaveDelay`, `editor.formatOnType` and `editor.linkedEditing`, with
  VS Code's defaults except `files.autoSave`, which is `afterDelay` here. Other settings no
  extension declares, such as the rest of `editor.*`, are neither offered nor reported.

- **`.vscode/tasks.json`**: the common task fields, a `type` that offers `shell`, `process` and every
  extension's `contributes.taskDefinitions` (with that definition's properties once chosen), and
  `problemMatcher` names from the built-in matchers and every `contributes.problemMatchers`.
- **`${...}` variables** in `launch.json` and `tasks.json`: typing `${` offers the standard
  variables, `env:`, `config:` with every declared setting, `input:` with the file's own `inputs`,
  and `command:` with the variables debuggers declare in `contributes.debuggers[].variables`.
- **Other files extensions declare schemas for** (`contributes.jsonValidation`), such as
  rust-analyzer's `rust-project.json`, matched by their `fileMatch` patterns. Schemas on the web or
  inside the extension are used; VS Code's own `vscode://schemas/...` ones are not available.

As in VS Code, these files -- and `.code-workspace` files and the user settings -- are JSON with
comments: comments and trailing commas are not reported in them.

The schemas and templates follow extensions as they are installed, removed, enabled and disabled.

**Tools | Open VS Code launch.json**, **Open VS Code tasks.json** and **Open VS Code Workspace
Settings (JSON)** open the project's file, creating it if there is none. In a multi-root workspace
they ask which, as VS Code does: the workspace's own, which opens the `.code-workspace` file at its
`launch`, `tasks` or `settings` (added if missing), or a folder's `.vscode/` file. A new `launch.json` starts
from the `initialConfigurations` of a debugger you pick, as VS Code's "create a launch.json file"
does, or empty; debuggers that build their first configurations in code are not asked.

## Launch configurations

1. Install the extension providing the desired debugger and its dependencies in **Settings → VS Code Extensions**, and
   enable them.
2. Add a named entry to `.vscode/launch.json` using that extension's debugger type and options.
3. Every configuration and compound in the file appears in the run widget, in a **launch.json**
   folder, under its own name; a compound named like a configuration gets "(compound)" after it.
   They follow the file as it is saved, and are read-only in **Run → Edit Configurations**: one
   edited, renamed or removed there is put back as the file has it. A **VS Code launch
   configuration** added by hand, naming an entry, is the user's and is left alone.
4. Start it using Run or Debug. Both start the extension's debug adapter. Run is VS Code's Run
   Without Debugging: the configuration carries `"noDebug": true`, and the program's output goes to
   the run console rather than a debugger. An `attach` configuration has Debug only, since without
   debugging an adapter attaches and does nothing else.

The entry is read from disk when used. The plugin selects the installed debugger extension,
reuses the project extension host, runs `preLaunchTask` if present (see below), and invokes extension debug
configuration logic before obtaining its adapter. The adapter's `launch` or `attach` request carries
the configuration as the extension resolved it. IntelliJ's DAP client consumes the adapter stream.
An adapter that asks the client to start the program (`runInTerminal`, as debugpy's
`integratedTerminal` console does) gets it started in a terminal of its own: a Run tool window tab
the program's output goes to, and that takes keyboard input. Once the program has ended the tab
stays, and the next run of the same name reuses it. An external terminal (`externalTerminal`) is a
window outside the IDE, as VS Code opens it: a console window on Windows, Terminal on macOS, and
on Linux GNOME's or KDE's terminal, `x-terminal-emulator` or xterm. It waits for Enter once the
program has ended. Where no window can be opened, the program runs in a tab instead.
VS Code's `terminal.external.*Exec` settings are not read.
Line breakpoints can be put where VS Code allows them: in a file whose language an enabled
extension lists under `contributes.breakpoints`. The file's language comes from an extension's
`contributes.languages`. With only a language extension installed, such as rust-analyzer, there is
nowhere to put one until a debugger extension such as CodeLLDB is installed. Languages that only
VS Code's built-in extensions define, such as C and C++, are not recognised.
Full IDE debugger behavior still needs end-to-end validation.

`launch` and `attach` entries are supported, and several sessions can run at once. Use unique
configuration names.

- **Compounds** run their `preLaunchTask` to the end, then start every member as a run of its own,
  with the executor the compound was started with. Each member's own `preLaunchTask` runs as well.
  With `stopAll`, the first member to stop stops the rest. A member's `folder` names the workspace
  folder whose `launch.json` holds it; without one, it is the compound's own.
- **`postDebugTask`** runs after the session ends. Nothing waits for it, and a failure or a
  non-zero exit is reported as a notification.
- **`debugServer`** must be a port number once variables are resolved. The extension host then
  connects to the adapter already listening on that port, instead of the extension's own adapter.
- **Child sessions** are sessions an adapter starts for each target it finds, such as js-debug's
  workers and subprocesses. A child runs from a configuration that is not kept in the run widget,
  the extension sees its parent, and the child stops when its parent ends.
- **Run | Attach to Process** lists a VS Code debugger next to IntelliJ's own for every local
  process, when the debugger declares an attach configuration (a snippet or an initial
  configuration) whose process comes from its process-picking variable: `${command:pickProcess}`,
  or a variant such as CodeLLDB's `pickMyProcess`. Attaching starts that configuration with the
  chosen process id in the variable's place, without a `launch.json` entry.

## Code lenses: Run and Debug

A lens's Run or Debug runs an extension command, which usually ends in one of two APIs:

- `vscode.debug.startDebugging`. The configuration, whether it is a `launch.json` name or one the
  extension wrote, becomes a temporary **VS Code launch configuration**. A name stays a reference
  to the `launch.json` entry; a configuration the extension wrote is kept as it was. That run
  configuration is started with Run when the extension asked for `noDebug`, and with Debug
  otherwise. It then stays in the run widget, so you can start it again.
- `vscode.tasks.executeTask`. A process or shell task becomes a temporary **VS Code task** run
  configuration, as a gutter icon's run does: it is selected in the run widget, runs in an ordinary
  Run tool window tab, and can be run again or saved. It holds the whole command, so it also runs
  without the extension. The extension is told when each run starts and ends, and the exit code.
  A custom execution runs the extension's pseudoterminal in a terminal tab. Problem matchers and
  tasks listed by `fetchTasks` are not supported.

A shell terminal an extension opens with `window.createTerminal` is a tab of the Terminal tool
window. A terminal the extension drives itself (a pseudoterminal) is a Run tool window tab with a
terminal emulator you can type into, as is every terminal when the Terminal plugin is disabled.

Variable substitution supports workspace/environment/configuration values and extension commands.
`${file}`, `${lineNumber}` and `${selectedText}` come from the editor you were in when the launch
started. `${input:...}` reads `launch.json`'s `inputs`, whichever configuration is being started.
Each input is asked once per launch, in a dialog on the client's screen. `promptString` (with
`password`) gets a text dialog, `pickString` gets a choice, and `command` runs the extension's
command. Canceling a dialog cancels the launch. The `inputs` of `tasks.json` are not read.
Unresolved variables are reported as errors. The final adapter-specific launch/attach behavior
still requires validation with the chosen debugger extension.

## Tasks before launching

For a portable, finite process task, put this in `.vscode/tasks.json`:

```json
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "prepare-demo",
      "type": "process",
      "command": "node",
      "args": [
        "scripts/prepare-demo.cjs"
      ],
      "options": {
        "cwd": "."
      }
    }
  ]
}
```

Provide the referenced script, then add `"preLaunchTask": "prepare-demo"` to your launch entry.
The command runs on the backend machine, with the workspace as its default working directory.

A `preLaunchTask` runs as the entry's **VS Code task** run configuration, the same kind a code lens's
task gets, temporary and named by its label. It runs in a Run tool window tab of its own, with its
output streamed. A foreground task must exit with code 0; a background task must report ready
through its problem matcher's background patterns. Stopping the task before readiness stops the
launch. The run widget keeps the configuration you chose. A
`tasks.json` task configuration reads the entry again on every run, so it can also be run, or saved,
on its own.

| Task feature                                               | Current behavior                                                                                                                                                                                                       |
|------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `process`                                                  | Command and arguments are passed as separate process arguments                                                                                                                                                         |
| `shell` (the default type)                                 | Uses `cmd.exe /d /c` on Windows or `/bin/sh -c` elsewhere; the command and arguments are quoted into one shell line                                                                                                    |
| `dependsOn`                                                | Dependencies run first, in order and each once, in the same tab; background dependencies wait for readiness while their processes keep running. The first failure stops the run. Missing labels and cycles are refused |
| `options.cwd`                                              | Resolved against the workspace                                                                                                                                                                                         |
| `options.env`                                              | Adds/overrides environment values; `null` removes a variable                                                                                                                                                           |
| `${workspaceFolder}` and the other workspace variables     | Substituted in the command, arguments, directory and environment                                                                                                                                                       |
| Other task types (`npm`, `cargo`, ...), interactive inputs | Not run from `tasks.json`; they need their extension's task provider                                                                                                                                                   |
| `isBackground` and problem matcher background patterns     | Inline `background`/`watching` blocks and named matchers from the pinned runtime or enabled extensions. `activeOnStart`, `beginsPattern` and `endsPattern` determine readiness                                         |
| Problem matcher diagnostics, build/test actions            | Not implemented; only background readiness is read from matchers                                                                                                                                                       |

Shell quoting is not equivalent to VS Code's full task engine. Prefer `process` with separate
arguments where possible.

For example, a shell task running `npx tsc --watch` can be a `preLaunchTask` with
`"isBackground": true` and `"problemMatcher": "$tsc-watch"`. The project must provide TypeScript.
For another watcher or development server, supply an inline problem matcher with a `background`
block: `beginsPattern` matches the start of work, and `endsPattern` matches the line that says it
is ready. With `activeOnStart: true`, the first end pattern can signal readiness without a begin
line. Patterns can be strings or objects with a `regexp` field. With several background matchers,
each must complete its first cycle.

A ready watcher stays in its Run tab until stopped or until it exits; ending debugging does not
stop it. Cancelling the launch while waiting stops its task group. A background `preLaunchTask`
without usable begin/end patterns is refused, and a watcher that exits before readiness prevents
launching even if its exit code is 0. Compilation diagnostics from problem matchers do not
block a launch; the readiness patterns alone decide when a running watcher is ready.

### Problems from task output

A task's `problemMatcher` -- a name, an object, or a list of either -- turns lines of its output
into problems drawn in the files they name, as VS Code's problem collectors do. Names resolve to
VS Code's own matchers (`$msCompile`, `$go`, `$eslint-stylish` and the rest), the pinned runtime's
built-in extensions' and enabled extensions' `contributes.problemMatchers`; a matcher's
`"pattern": "$name"` resolves the same way against `contributes.problemPatterns`. `base`, `owner`,
`source`, `severity`, `applyTo`, multi-line patterns with `loop`, and every `fileLocation` kind (`absolute`, `relative`,
`autoDetect`, `search`, with `${cwd}` the task's own directory) are read.
The matchers an extension names for a task it runs work the same way.

Problems are filed by matcher owner. A rerun replaces an owner's problems file by file as it finds
them, and withdraws those in files it never mentions once it ends; a background task does this
compile by compile, between its `beginsPattern` and `endsPattern`. They stay after the run, and
after the extension host restarts. A matcher that cannot be read is reported on the task's console,
and the task still runs.

Not supported: listing problems in files that are not open (the Problems view shows the open
file's), handing them to extensions through `languages.getDiagnostics`, a `beginsPattern`'s `file`
group, and JavaScript-only regular expression syntax such as `[^]`.

## Workspace settings

`.vscode/settings.json` supplies the host workspace configuration and launch `${config:...}` lookup.
Saved changes now notify running extensions, including changes made outside the IDE. Atomic-save
events are coalesced before reload; invalid JSONC retains the last valid snapshot and is logged.
Extension-contributed defaults and language overrides are included. An extension's
`configuration.update` writes the one key to `.vscode/settings.json` or the user settings file, through
the editor when the file is open. In a multi-root workspace the layers are applied as described
under [Multi-root workspaces](#multi-root-workspaces). See [core services](core-workbench-services.md).

For language support, see the [runtime and LSP reference](runtime-and-lsp.md), which covers document
ownership and the current limits of language support.
