package me.helvert.codebridge.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import me.helvert.codebridge.icons.Codicons.KNOWN
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * VS Code's icons, codicons, as IntelliJ icons.
 *
 * Extensions name codicons everywhere they can show an icon: `$(name)` in a status bar item,
 * a CodeLens title or a tree item's label, and a `ThemeIcon` wherever an icon is an argument. Those
 * the IDE has an icon for the same thing are drawn with it, so they look native and follow the
 * theme; any other is drawn as the codicon itself, in the IDE's icon colours, and logged once so a
 * fitting IDE icon can be added here. A name that is no codicon is one an extension contributes
 * ([ContributedIcons]), and drawn as [DEFAULT] when none does.
 */
object Codicons {

    /** One `$(name)` or `$(name~modifier)` reference; `~spin` is the only modifier VS Code has. */
    data class Reference(val name: String, val spin: Boolean)

    private val REFERENCE = Regex("""\$\(([A-Za-z0-9-]+)(?:~([a-z]+))?\)""")

    /** The icon for a name that is no codicon, the IDE's or VS Code's, nor contributed by an extension. */
    val DEFAULT: Icon = AllIcons.Nodes.Unknown

    /** The codicon references in [text], in order. */
    fun references(text: String): List<Reference> =
        REFERENCE.findAll(text).map { Reference(it.groupValues[1], it.groupValues[2] == "spin") }.toList()

    /** [text] with its codicon references taken out. */
    fun strip(text: String): String = REFERENCE.replace(text, "")

    /** [html] with each codicon reference an `<icon src="codicon:name">` that [iconOf] draws. */
    fun htmlIcons(html: String): String =
        REFERENCE.replace(html) { """<icon src="$SOURCE${it.value.removePrefix("$(").removeSuffix(")")}"/>""" }

    /** The icon an `<icon src="codicon:name">` stands for, or null for any other source. */
    fun iconOf(src: String): Icon? {
        if (!src.startsWith(SOURCE)) return null
        return references("$(${src.removePrefix(SOURCE)})").firstOrNull()?.let(::icon)
    }

    private const val SOURCE = "codicon:"

    /** The icon for [reference]: IntelliJ's spinner when it spins, whatever the codicon. */
    fun icon(reference: Reference): Icon = if (reference.spin) AnimatedIcon.Default.INSTANCE else icon(reference.name)

    /**
     * The icon for codicon [name]: the IDE's own when there is one, then VS Code's. Any other name is
     * an icon an extension contributes, drawn as [DEFAULT] while no enabled extension does.
     */
    fun icon(name: String): Icon {
        known(name)?.let { return it }
        val bundled = bundled(name) ?: return ContributedIcons.icon(name)
        if (unknown.add(name)) LOG.info("No IDE icon for codicon `$name`; drawn as VS Code's")
        return bundled
    }

    /**
     * VS Code's own drawing of codicon [name], or of the codicon it is an alias of, from the copy
     * of `@vscode/codicons` under `/codicons`; null for a name that is no codicon.
     */
    fun bundled(name: String): Icon? {
        val drawn = BUNDLED_ALIASES.getProperty(name) ?: name
        // The name comes from an extension, so it must not reach outside `/codicons`.
        if (!NAME.matches(drawn)) return null
        val path = "/codicons/$drawn.svg"
        if (Codicons::class.java.getResource(path) == null) return null
        return IconLoader.getIcon(path, Codicons::class.java.classLoader)
    }

    /** The IDE's icon for codicon [name], or for the codicon it is an alias of: `console` is `terminal`. */
    fun known(name: String): Icon? = KNOWN[name] ?: ALIASES[name]?.let(KNOWN::get)

    /**
     * Codicons that have an IntelliJ icon for the same thing. Only close matches: an icon that
     * means something else in the IDE would mislead where the default icon would not.
     */
    private val KNOWN: Map<String, Icon> = mapOf(
        // Status
        "error" to AllIcons.General.Error,
        "error-compact" to AllIcons.General.Error,
        "error-small" to AllIcons.General.Error,
        "warning" to AllIcons.General.Warning,
        "warning-compact" to AllIcons.General.Warning,
        "info" to AllIcons.General.Information,
        "check" to AllIcons.General.InspectionsOK,
        "check-compact" to AllIcons.General.InspectionsOK,
        "pass" to AllIcons.General.InspectionsOK,
        "pass-compact" to AllIcons.General.InspectionsOK,
        "pass-filled" to AllIcons.RunConfigurations.TestPassed,
        "pass-filled-compact" to AllIcons.RunConfigurations.TestPassed,
        "question" to AllIcons.Actions.Help,
        // A slashed ring, as GitLens's "Hide": the IDE draws the same one for an invalid breakpoint.
        "circle-slash" to AllIcons.Debugger.Db_invalid_breakpoint,
        "circle-slash-compact" to AllIcons.Debugger.Db_invalid_breakpoint,
        "verified" to AllIcons.CodeWithMe.CwmVerified,
        "verified-filled" to AllIcons.CodeWithMe.CwmVerified,
        // Something still loading, spun or not: the IDE shows one only while it spins.
        "loading" to AnimatedIcon.Default.INSTANCE,
        "loading-compact" to AnimatedIcon.Default.INSTANCE,
        "flag" to AllIcons.Actions.Report,
        "report" to AllIcons.Actions.Report,
        "bell" to AllIcons.Toolwindows.Notifications,
        "bell-dot" to AllIcons.Toolwindows.NotificationsNew,
        "feedback" to AllIcons.Ide.Feedback,
        "gift" to AllIcons.Ide.Gift,
        // Announcements, as GitLens's "What's New": the IDE has no icon of its own for one.
        "megaphone" to AllIcons.Ide.Gift,
        "thumbsup" to AllIcons.Ide.Like,
        "thumbsdown" to AllIcons.Ide.Dislike,
        // Editing
        "add" to AllIcons.General.Add,
        "add-compact" to AllIcons.General.Add,
        "add-small" to AllIcons.General.Add,
        "remove" to AllIcons.General.Remove,
        "remove-small" to AllIcons.General.Remove,
        "close" to AllIcons.Actions.Close,
        "close-compact" to AllIcons.Actions.Close,
        "close-small" to AllIcons.General.CloseSmall,
        "trash" to AllIcons.General.Delete,
        "save" to AllIcons.Actions.MenuSaveall,
        "clippy" to AllIcons.Actions.MenuPaste,
        "attach" to AllIcons.Actions.Attach,
        "attach-compact" to AllIcons.Actions.Attach,
        "newline" to AllIcons.Actions.SearchNewLine,
        "wand" to AllIcons.Diff.MagicResolveToolbar,
        "keyboard" to AllIcons.General.Keyboard,
        "eye" to AllIcons.General.Show,
        "list-filter" to AllIcons.General.Filter,
        "arrow-both" to AllIcons.Diff.ArrowLeftRight,
        "symbol-color" to AllIcons.Gutter.Colors,
        "tools" to AllIcons.General.ExternalTools,
        "target" to AllIcons.General.Locate,
        "three-bars" to AllIcons.General.Menu,
        "blank" to AllIcons.Empty,
        "edit" to AllIcons.Actions.Edit,
        "edit-compact" to AllIcons.Actions.Edit,
        "copy" to AllIcons.Actions.Copy,
        "redo" to AllIcons.Actions.Redo,
        "search" to AllIcons.Actions.Search,
        "search-compact" to AllIcons.Actions.Search,
        "search-large" to AllIcons.Actions.Search,
        "replace" to AllIcons.Actions.Replace,
        "case-sensitive" to AllIcons.Actions.MatchCase,
        "whole-word" to AllIcons.Actions.Words,
        "regex" to AllIcons.Actions.Regex,
        "preserve-case" to AllIcons.Actions.PreserveCase,
        "filter" to AllIcons.General.Filter,
        "filter-filled" to AllIcons.General.Filter,
        "word-wrap" to AllIcons.Actions.ToggleSoftWrap,
        "new-file" to AllIcons.Actions.AddFile,
        "new-folder" to AllIcons.Actions.NewFolder,
        "go-to-file" to AllIcons.Actions.EditSource,
        "save-all" to AllIcons.Actions.MenuSaveall,
        "open-preview" to AllIcons.Actions.Preview,
        "preview" to AllIcons.Actions.Preview,
        "export" to AllIcons.General.Export,
        "download" to AllIcons.Actions.Download,
        "cloud-download" to AllIcons.Actions.Download,
        "desktop-download" to AllIcons.Actions.Download,
        "cloud-download-compact" to AllIcons.Actions.Download,
        "cloud-upload" to AllIcons.Actions.Upload,
        "cloud-upload-compact" to AllIcons.Actions.Upload,
        "cloud" to AllIcons.Build.CompletionCloud,
        "link" to AllIcons.Ide.Link,
        "link-external" to AllIcons.Ide.External_link_arrow,
        "clear-all" to AllIcons.Actions.GC,
        "fold" to AllIcons.Gutter.Fold,
        "unfold" to AllIcons.Gutter.Unfold,
        "collapse-all" to AllIcons.Actions.Collapseall,
        "collapse-all-compact" to AllIcons.Actions.Collapseall,
        "expand-all" to AllIcons.Actions.Expandall,
        "list-tree" to AllIcons.Actions.ShowAsTree,
        "split-horizontal" to AllIcons.Actions.SplitHorizontally,
        "split-vertical" to AllIcons.Actions.SplitVertically,
        // "Open in New Window": the IDE's own action for moving an editor to a window of its own.
        "empty-window" to AllIcons.Actions.MoveToWindow,
        "open-in-window" to AllIcons.Actions.MoveToWindow,
        "zoom-in" to AllIcons.General.ZoomIn,
        "zoom-out" to AllIcons.General.ZoomOut,
        "pin" to AllIcons.General.Pin,
        "pinned" to AllIcons.General.Pin,
        "menu" to AllIcons.General.Menu,
        "ellipsis" to AllIcons.Actions.MoreHorizontal,
        "kebab-vertical" to AllIcons.Actions.More,
        "gripper" to AllIcons.General.Drag,
        "chevron-up" to AllIcons.General.ChevronUp,
        "chevron-up-compact" to AllIcons.General.ChevronUp,
        "chevron-down" to AllIcons.General.ChevronDown,
        "chevron-down-compact" to AllIcons.General.ChevronDown,
        "chevron-left" to AllIcons.General.ChevronLeft,
        "chevron-left-compact" to AllIcons.General.ChevronLeft,
        "chevron-right" to AllIcons.General.ChevronRight,
        "chevron-right-compact" to AllIcons.General.ChevronRight,
        "arrow-up" to AllIcons.General.ArrowUp,
        "arrow-up-compact" to AllIcons.General.ArrowUp,
        "arrow-small-up" to AllIcons.General.ArrowUp,
        "arrow-down" to AllIcons.General.ArrowDown,
        "arrow-small-down" to AllIcons.General.ArrowDown,
        "arrow-left" to AllIcons.General.ArrowLeft,
        "arrow-small-left" to AllIcons.General.ArrowLeft,
        "arrow-right" to AllIcons.General.ArrowRight,
        "arrow-small-right" to AllIcons.General.ArrowRight,
        "chrome-close" to AllIcons.Windows.Close,
        "chrome-maximize" to AllIcons.Windows.Maximize,
        "chrome-minimize" to AllIcons.Windows.Minimize,
        "chrome-restore" to AllIcons.Windows.Restore,
        "settings" to AllIcons.General.Settings,
        "settings-gear" to AllIcons.General.Settings,
        "settings-compact" to AllIcons.General.Settings,
        "gear" to AllIcons.General.Settings,
        "gear-compact" to AllIcons.General.Settings,
        "lightbulb" to AllIcons.Actions.IntentionBulb,
        "lightbulb-compact" to AllIcons.Actions.IntentionBulb,
        "lightbulb-autofix" to AllIcons.Actions.QuickfixBulb,
        "lightbulb-sparkle" to AllIcons.Actions.AiIntentionBulb,
        "lightbulb-empty" to AllIcons.Actions.IntentionBulbGrey,
        // Files and project structure
        "file" to AllIcons.FileTypes.Any_type,
        "symbol-file" to AllIcons.FileTypes.Any_type,
        "file-text" to AllIcons.FileTypes.Text,
        "file-binary" to AllIcons.FileTypes.BinaryData,
        "file-media" to AllIcons.FileTypes.Image,
        "file-media-compact" to AllIcons.FileTypes.Image,
        "file-zip" to AllIcons.FileTypes.Archive,
        "file-symlink-file" to AllIcons.Nodes.Symlink,
        "file-symlink-directory" to AllIcons.Nodes.Symlink,
        "json" to AllIcons.FileTypes.Json,
        "markdown" to AllIcons.FileTypes.Markdown,
        "python" to AllIcons.Language.Python,
        "ruby" to AllIcons.Language.Ruby,
        "folder" to AllIcons.Nodes.Folder,
        "folder-compact" to AllIcons.Nodes.Folder,
        "folder-opened" to AllIcons.Nodes.Folder,
        "folder-opened-compact" to AllIcons.Nodes.Folder,
        "folder-library" to AllIcons.Nodes.PpLibFolder,
        "library" to AllIcons.Nodes.PpLib,
        "library-compact" to AllIcons.Nodes.PpLib,
        // The same picture, a folder in a folder, in the IDE's style.
        "file-submodule" to AllIcons.Nodes.CopyOfFolder,
        // A project, or a folder at the root of the workspace: what the IDE shows as a module.
        "project" to AllIcons.Nodes.Module,
        "project-compact" to AllIcons.Nodes.Module,
        "root-folder" to AllIcons.Nodes.Module,
        "root-folder-opened" to AllIcons.Nodes.Module,
        "exclude" to AllIcons.Modules.ExcludeRoot,
        "home" to AllIcons.Nodes.HomeFolder,
        "extensions" to AllIcons.Nodes.Plugin,
        "extensions-large" to AllIcons.Nodes.Plugin,
        "mcp" to AllIcons.Nodes.McpServer,
        "bookmark" to AllIcons.Nodes.Bookmark,
        "tag" to AllIcons.Nodes.Tag,
        "lock" to AllIcons.Nodes.Locked,
        "lock-small" to AllIcons.Nodes.Locked,
        "unlock" to AllIcons.Ide.Readwrite,
        "key" to AllIcons.CodeWithMe.CwmPermissions,
        "star" to AllIcons.Nodes.NotFavoriteOnHover,
        "star-full" to AllIcons.Nodes.Favorite,
        "account" to AllIcons.General.User,
        "person" to AllIcons.General.User,
        // People, as GitLens's "View Contributors": the IDE has no icon of several, and its
        // `General.Groups` is four squares.
        "organization" to AllIcons.General.User,
        "sign-out" to AllIcons.Actions.Exit,
        // Live Share and sharing: Code With Me is the IDE's own of both.
        "live-share" to AllIcons.CodeWithMe.CwmShared,
        "share" to AllIcons.CodeWithMe.CwmShared,
        "device-camera-video" to AllIcons.CodeWithMe.CwmCamOn,
        "mic" to AllIcons.CodeWithMe.CwmMicOn,
        "mic-filled" to AllIcons.CodeWithMe.CwmMicOn,
        "mic-compact" to AllIcons.CodeWithMe.CwmMicOn,
        "mic-off" to AllIcons.CodeWithMe.CwmMicOff,
        "mic-off-compact" to AllIcons.CodeWithMe.CwmMicOff,
        "device-desktop" to AllIcons.Nodes.Desktop,
        "server" to AllIcons.Webreferences.Server,
        "globe" to AllIcons.General.Web,
        "github" to AllIcons.Vcs.Vendors.Github,
        "github-inverted" to AllIcons.Vcs.Vendors.Github,
        "github-alt" to AllIcons.Vcs.Vendors.Github,
        "azure" to AllIcons.Providers.Azure,
        "microsoft" to AllIcons.Providers.Microsoft,
        // Symbols
        "symbol-class" to AllIcons.Nodes.Class,
        "symbol-interface" to AllIcons.Nodes.Interface,
        "symbol-enum" to AllIcons.Nodes.Enum,
        "symbol-enum-member" to AllIcons.Nodes.Field,
        "symbol-field" to AllIcons.Nodes.Field,
        "symbol-property" to AllIcons.Nodes.Property,
        "symbol-method" to AllIcons.Nodes.Method,
        "symbol-function" to AllIcons.Nodes.Function,
        "symbol-variable" to AllIcons.Nodes.Variable,
        "symbol-constant" to AllIcons.Nodes.Constant,
        "symbol-parameter" to AllIcons.Nodes.Parameter,
        "symbol-namespace" to AllIcons.Nodes.Package,
        "symbol-package" to AllIcons.Nodes.Package,
        "symbol-module" to AllIcons.Nodes.Module,
        "symbol-snippet" to AllIcons.Nodes.Template,
        "symbol-array" to AllIcons.Json.Array,
        "symbol-event" to AllIcons.Actions.Lightning,
        "table" to AllIcons.Nodes.DataTables,
        "code" to AllIcons.Toolwindows.ToolWindowDevMode,
        // Callers and callees: the IDE's call hierarchy draws them as super- and subtypes.
        "call-incoming" to AllIcons.Hierarchy.Supertypes,
        "call-outgoing" to AllIcons.Hierarchy.Subtypes,
        "type-hierarchy" to AllIcons.Toolwindows.ToolWindowHierarchy,
        "type-hierarchy-sub" to AllIcons.Hierarchy.Subtypes,
        "type-hierarchy-super" to AllIcons.Hierarchy.Supertypes,
        // Version control
        // The Source Control view: what the IDE's own VCS tool window shows.
        "source-control" to AllIcons.Toolwindows.ToolWindowChanges,
        "history" to AllIcons.Vcs.History,
        "clockface" to AllIcons.Vcs.History,
        "diff" to AllIcons.Actions.Diff,
        "git-compare" to AllIcons.Actions.Diff,
        "compare-changes" to AllIcons.Actions.Diff,
        "git-commit" to AllIcons.Actions.Commit,
        "git-branch" to AllIcons.Vcs.Branch,
        "git-branch-compact" to AllIcons.Vcs.Branch,
        "git-merge" to AllIcons.Vcs.Merge,
        "merge" to AllIcons.Vcs.Merge,
        "git-fetch" to AllIcons.Vcs.Fetch,
        "repo-fetch" to AllIcons.Vcs.Fetch,
        "repo-push" to AllIcons.Vcs.Push,
        // A pull: what the IDE's Update Project does.
        "repo-pull" to AllIcons.Actions.CheckOut,
        // A stash: the IDE's own of the same is the shelf.
        "git-stash" to AllIcons.Vcs.ShelveSilent,
        "git-stash-apply" to AllIcons.Vcs.Unshelve,
        "git-stash-pop" to AllIcons.Vcs.Unshelve,
        "repo-clone" to AllIcons.Vcs.Clone,
        "discard" to AllIcons.Actions.Rollback,
        // Running and debugging
        "play" to AllIcons.Actions.Execute,
        "run" to AllIcons.Actions.Execute,
        "run-compact" to AllIcons.Actions.Execute,
        "debug-start" to AllIcons.Actions.Execute,
        "run-all" to AllIcons.Actions.RunAll,
        "run-coverage" to AllIcons.General.RunWithCoverage,
        "run-all-coverage" to AllIcons.General.RunWithCoverage,
        "debug-coverage" to AllIcons.General.RunWithCoverage,
        "coverage" to AllIcons.Toolwindows.ToolWindowCoverage,
        "build" to AllIcons.Actions.Compile,
        "terminal" to AllIcons.Debugger.Console,
        "terminal-compact" to AllIcons.Debugger.Console,
        // A terminal profile's shell, by its logo: the IDE draws every terminal the same.
        "terminal-bash" to AllIcons.Debugger.Console,
        "terminal-cmd" to AllIcons.Debugger.Console,
        "terminal-debian" to AllIcons.Debugger.Console,
        "terminal-git-bash" to AllIcons.Debugger.Console,
        "terminal-linux" to AllIcons.Debugger.Console,
        "terminal-powershell" to AllIcons.Debugger.Console,
        "terminal-secure" to AllIcons.Debugger.Console,
        "terminal-tmux" to AllIcons.Debugger.Console,
        "terminal-ubuntu" to AllIcons.Debugger.Console,
        "output" to AllIcons.Debugger.Console,
        "debug-console" to AllIcons.Debugger.Console,
        "sync" to AllIcons.Actions.Refresh,
        "sync-compact" to AllIcons.Actions.Refresh,
        "refresh" to AllIcons.Actions.Refresh,
        "refresh-compact" to AllIcons.Actions.Refresh,
        "debug" to AllIcons.Actions.StartDebugger,
        "bug" to AllIcons.Toolwindows.ToolWindowDebugger,
        "debug-alt" to AllIcons.Actions.StartDebugger,
        "debug-alt-small" to AllIcons.Actions.StartDebugger,
        "debug-rerun" to AllIcons.Actions.Rerun,
        "debug-restart" to AllIcons.Actions.Restart,
        "debug-restart-frame" to AllIcons.Actions.RestartFrame,
        "debug-pause" to AllIcons.Actions.Pause,
        "debug-continue" to AllIcons.Actions.Resume,
        "debug-continue-small" to AllIcons.Actions.Resume,
        "debug-step-over" to AllIcons.Actions.TraceOver,
        "debug-step-into" to AllIcons.Actions.TraceInto,
        "debug-step-out" to AllIcons.Actions.StepOut,
        "debug-stop" to AllIcons.Actions.Suspend,
        "stop-circle" to AllIcons.Actions.Suspend,
        "debug-stackframe" to AllIcons.Debugger.Frame,
        "debug-stackframe-active" to AllIcons.Debugger.Frame,
        "record" to AllIcons.Profiler.Rec,
        "record-small" to AllIcons.Profiler.Rec,
        "watch" to AllIcons.Debugger.Watch,
        "activate-breakpoints" to AllIcons.Debugger.MuteBreakpoints,
        "debug-breakpoint" to AllIcons.Debugger.Db_set_breakpoint,
        "debug-breakpoint-disabled" to AllIcons.Debugger.Db_disabled_breakpoint,
        "debug-breakpoint-unverified" to AllIcons.Debugger.Db_invalid_breakpoint,
        "debug-breakpoint-unsupported" to AllIcons.Debugger.Db_invalid_breakpoint,
        "debug-breakpoint-conditional-unverified" to AllIcons.Debugger.Db_invalid_breakpoint,
        "debug-breakpoint-data-unverified" to AllIcons.Debugger.Db_invalid_breakpoint,
        "debug-breakpoint-function-unverified" to AllIcons.Debugger.Db_invalid_breakpoint,
        "debug-breakpoint-log-unverified" to AllIcons.Debugger.Db_invalid_breakpoint,
        "debug-breakpoint-function" to AllIcons.Debugger.Db_method_breakpoint,
        "debug-breakpoint-data" to AllIcons.Debugger.Db_field_breakpoint,
    )

    /** Codicon aliases, from `@vscode/codicons`' own mapping, of codicons in [KNOWN]. */
    private val ALIASES: Map<String, String> = mapOf(
        "alert" to "warning",
        "stop" to "error",
        "plus" to "add",
        "x" to "close",
        "remove-close" to "close",
        "pencil" to "edit",
        "light-bulb" to "lightbulb",
        "trashcan" to "trash",
        "record-keys" to "keyboard",
        "eye-watch" to "eye",
        "eye-unwatch" to "eye",
        "star-empty" to "star",
        "star-add" to "star",
        "star-delete" to "star",
        "log-out" to "sign-out",
        "vm" to "device-desktop",
        "array" to "symbol-array",
        "zap" to "symbol-event",
        "more" to "ellipsis",
        "kebab-horizontal" to "ellipsis",
        "file-add" to "new-file",
        "file-directory" to "folder",
        "symbol-folder" to "folder",
        "file-directory-create" to "new-folder",
        "person-filled" to "person",
        "person-follow" to "person",
        "person-outline" to "person",
        "logo-github" to "github",
        "mark-github" to "github",
        "symbol-constructor" to "symbol-method",
        "symbol-type-parameter" to "symbol-parameter",
        "variable" to "symbol-variable",
        "clock" to "history",
        "diff-sidebyside" to "diff",
        "repo-sync" to "sync",
        "console" to "terminal",
        "repl" to "terminal",
        "debug-stackframe-focused" to "debug-stackframe",
    )

    private val NAME = Regex("[a-z0-9-]+")

    /** Every alias `@vscode/codicons` has, of the codicon it draws, for [bundled]. */
    private val BUNDLED_ALIASES = Properties().apply {
        Codicons::class.java.getResourceAsStream("/codicons/aliases.properties")?.use(::load)
    }

    private val LOG = logger<Codicons>()
    private val unknown = ConcurrentHashMap.newKeySet<String>()
}
