import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    // The root project has no code of its own, only the descriptor; the modules bring the Kotlin.
    java
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.jvm") apply false
    id("rpc") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
}

/**
 * The plugin's own modules.
 *
 * `ui-test` is a subproject but not one of these: it has no main sources, ships nothing, and only
 * exists to launch two IDEs and ask the client what it drew.
 */
val pluginModules = subprojects.filter { it.name !in setOf("ui-test", "runtime", "bundled") }

/**
 * Shows the IDE each module once, as a module, rather than also as a library of its own jar.
 *
 * The IntelliJ Platform module plugin asks every project dependency for the module's composed jar,
 * `CodeBridge.<module>.jar`, which ends up on the runtime classpaths. The IDE cannot trace that jar
 * back to the module's sources, so a sync lists it as a library beside the module dependency, and
 * every class shows up twice: once in the sources, once in a jar as old as the last build. During
 * a sync alone, project dependencies ask for the plain jar instead, which the IDE does recognise.
 * Builds, tests and the sandboxes still use the composed jar.
 */
if (System.getProperty("idea.sync.active").toBoolean()) {
    allprojects {
        pluginManager.withPlugin("org.jetbrains.intellij.platform.module") {
            configurations.configureEach {
                dependencies.withType<ProjectDependency>().configureEach {
                    attributes {
                        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                    }
                }
            }
        }
    }
}

/**
 * What every checkout on a machine can share rather than fetch or unpack again: worktrees and fresh
 * clones start from it. `-PcodebridgeCacheDir=<dir>` puts it elsewhere; by default it is in the
 * Gradle user home, where WSL keeps its Linux IDEs apart from Windows' ones.
 *
 * Only what is named by its version goes here. A sandbox, or anything else a checkout builds, stays in it.
 */
val sharedCacheDirectory: File by extra(
    providers.gradleProperty("codebridgeCacheDir").map(::File).getOrElse(gradle.gradleUserHomeDir.resolve("codebridge"))
)

/**
 * The IDEs the build runs against, unpacked once per machine.
 *
 * The IntelliJ Platform plugin keeps the downloaded archives in the Gradle user home already, but
 * unpacks each one, four to five GB, under the checkout's own `.intellijPlatform`.
 */
allprojects {
    pluginManager.withPlugin("org.jetbrains.intellij.platform.base") {
        extensions.configure<IntelliJPlatformExtension> {
            caching.ides.path = sharedCacheDirectory.resolve("ides")
        }
    }
}

// The plugins block cannot apply plugins to subprojects.
@Suppress("AvoidApplyPluginMethod")
subprojects {
    if (name in setOf("ui-test", "runtime", "bundled")) return@subprojects
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "rpc")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

    // Interface default methods only, as the platform compiles its own. The default mode also has a
    // class implementing a platform interface override each of its default methods with a call to
    // super, so the Plugin Verifier reports every deprecated one as overridden and invoked here.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }

    dependencies {
        testImplementation(platform(rootProject.libs.junit.bom))
    }

    /**
     * Throws away compiled test classes so the next run really recompiles them.
     *
     * Incremental compilation does not notice every change a test depends on: adding a defaulted
     * parameter to a data class in `main` changes the constructor's JVM signature, and the tests
     * calling it were still served from an earlier compilation -- passing, then failing with
     * `NoSuchMethodError` only once the output was deleted by hand. The instrumented output caches
     * the same staleness a second time. A test run that silently reports last week's code is worse
     * than no test run, so `gradlew freshTest` exists to rule it out.
     */
    // Read now, not inside the task: the configuration cache forbids touching `project` at
    // execution time.
    val hasTests = file("src/test").isDirectory
    val cleanTestClasses = tasks.register<Delete>("cleanTestClasses") {
        description = "Deletes compiled test classes, so that the next test run recompiles them."
        // Only where there are tests to recompile: emptying these in a module without any leaves
        // `instrumentTestCode` failing with "1 >= 1".
        onlyIf { hasTests }
        // Compiled test classes and the instrumented copy of them. Not `build/kotlin`, which holds
        // the incremental build history: deleting that leaves `instrumentCode` failing the same way.
        delete(
            layout.buildDirectory.dir("classes/kotlin/test"),
            layout.buildDirectory.dir("instrumented"),
        )
    }
    tasks.matching { it.name.startsWith("compile") || it.name.startsWith("instrument") }
        .configureEach { mustRunAfter(cleanTestClasses) }
}

/**
 * Every module's tests, from a clean compilation. Slower than `test`, and trustworthy.
 *
 * Not `ui-test`: that one builds the plugin, downloads an IDE and a client and starts both, which
 * is minutes of work, so it runs only when `:ui-test:test` is asked for by name.
 */
tasks.register("freshTest") {
    group = "verification"
    description = "Recompiles all test sources from scratch and runs every plugin module's tests."
    dependsOn(pluginModules.map { it.tasks.named("cleanTestClasses") })
    dependsOn(pluginModules.map { it.tasks.named("test") })
}

// The root project has no code of its own, only the descriptor, kept under src/ beside the modules.
sourceSets.main {
    resources.setSrcDirs(listOf("src/vscode/plugin/resources"))
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.idea.get())

        // The runtime is a plugin of its own, which this one depends on and installs beside itself.
        localPlugin(project(":runtime"))

        // Not transitive: these modules build on the runtime's, which must not be packed in here too.
        pluginModule(implementation(project(":json.backend")) { isTransitive = false })
        pluginModule(implementation(project(":marketplace.shared")) { isTransitive = false })
        pluginModule(implementation(project(":marketplace.backend")) { isTransitive = false })
        pluginModule(implementation(project(":marketplace.frontend")) { isTransitive = false })
        pluginModule(implementation(project(":workspace.shared")) { isTransitive = false })
        pluginModule(implementation(project(":workspace.backend")) { isTransitive = false })
        pluginModule(implementation(project(":workspace.frontend")) { isTransitive = false })
    }
}

/**
 * Split mode is the target, but not the only way to run.
 *
 * Language features used to be unjudgeable from a JetBrains Client window, because they were routed
 * through `intellij.platform.lsp`, which has no frontend counterpart in the distribution. They are
 * now an annotator and a documentation target, which the platform forwards to a split frontend like
 * any other backend-computed feature -- untested there, so far. `-PsplitMode=false` runs the
 * monolith, which is the simpler process to attach to and read a log from.
 */
val runSplit = providers.gradleProperty("splitMode").map(String::toBoolean).getOrElse(true)

intellijPlatform {
    splitMode = runSplit
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH

    // `verifyPlugin` checks against the platform the plugin is built with, which is the only one
    // it supports; the report lists internal, experimental and deprecated API usages.
    pluginVerification {
        ides {
            current()
        }
        // The default levels, and no internal API at all: what needs it lives in the runtime's
        // `internal.backend`. Overriding a non-extendable method is never meant to slip in either.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
    }
}

/**
 * Other IDEs to run the plugin in, each as a `run<Name>` task beside `runIde`: `gradlew runRustRover`.
 *
 * IDEA's own language support is tangled with what an extension for the same language does, and
 * cannot be switched off: Java is marked essential, and the rest are needed by other plugins. An IDE without Java support is the one place to see
 * the extension alone. Each is pinned to the build of the same platform branch as `intellijIdea(...)`
 * above, since the plugin is built against that and nothing older; bump them together. The builds
 * are listed at https://data.services.jetbrains.com/products/releases?code=RR&type=eap,release.
 *
 * `-P<name>Version=<build>` runs another build without editing this, `-P<name>Path=<dir>` an IDE
 * already installed; `-PsplitMode` applies as it does to `runIde`.
 *
 * The Gradle plugin only gives `runIde` its `runIdeBackend` and `runIdeFrontend` halves, so each IDE
 * here gets its own `run<Name>Backend` and `run<Name>Frontend`, to be debugged as both processes the
 * way IDEA is. Every run task shares `runIde`'s sandbox directory, apart from its own suffixed
 * config and plugins, so the frontend finds the join link its backend writes there -- and only one
 * split-mode backend, of whichever IDE, can run at a time.
 */
val alternativeIdes = mapOf(
    IntelliJPlatformType.RustRover to "263.7031.25",
)

intellijPlatformTesting {
    runIde {
        alternativeIdes.forEach { (ide, build) ->
            val property = ide.name.replaceFirstChar(Char::lowercase)
            val path = providers.gradleProperty("${property}Path")
            val version = providers.gradleProperty("${property}Version").getOrElse(build)
            mapOf(
                "" to (runSplit to SplitModeAware.PluginInstallationTarget.BOTH),
                "Backend" to (true to SplitModeAware.PluginInstallationTarget.BACKEND),
                "Frontend" to (true to SplitModeAware.PluginInstallationTarget.FRONTEND),
            ).forEach { (part, target) ->
                register("run${ide.name}$part") {
                    type = ide
                    if (path.isPresent) localPath.set(file(path.get()))
                    else this.version = version
                    splitMode = target.first
                    pluginInstallationTarget = target.second
                }
            }
        }
    }
}

alternativeIdes.keys.forEach { ide ->
    tasks.named<RunIdeTask>("run${ide.name}Backend") {
        description = "Runs the ${ide.name} backend process in Split Mode."
        executionMode = RunIdeTask.ExecutionMode.SPLIT_MODE_BACKEND
    }
    tasks.named<RunIdeTask>("run${ide.name}Frontend") {
        description = "Runs the JetBrains Client frontend process of ${ide.name} in Split Mode."
        executionMode = RunIdeTask.ExecutionMode.SPLIT_MODE_FRONTEND
        applyFrontendSandboxFrom(tasks.named<PrepareSandboxTask>("prepareSandbox_run${ide.name}Frontend"))
    }
}

/** The plugin's licence and the third-party notices it is distributed under, at its root. */
tasks.withType<BuildPluginTask>().configureEach {
    from("LICENSE", "NOTICE.md")
}

/** The build machine's runtime, unpacked by `:runtime:extractHostRuntime`; see there. */
val hostRuntimeDirectory = layout.buildDirectory.dir("vscodium/host")

/** Points a JVM that runs the plugin from a sandbox at the unpacked runtime. */
fun JavaForkOptions.usesHostRuntime() {
    systemProperty("codebridge.runtime.dir", hostRuntimeDirectory.get().asFile.absolutePath)
}

/**
 * Hands every test JVM the unpacked runtime, and unpacks it only when asked to.
 *
 * A plain `gradlew test` should not reach for 113 MB of network, so the download and extraction run
 * only with `CODEBRIDGE_TEST_LSP_HOST=1`. A runtime unpacked by an earlier run is used either way:
 * the property is set regardless, and `BundledRuntime` treats a directory that is not there as no
 * runtime at all.
 */
val fetchTestRuntime = providers.environmentVariable("CODEBRIDGE_TEST_LSP_HOST").map { it == "1" }.getOrElse(false)
allprojects {
    tasks.withType<Test>().configureEach {
        if (fetchTestRuntime) dependsOn(":runtime:extractHostRuntime")
        usesHostRuntime()
        // Wherever the module is: the modules sit at different depths under src/.
        systemProperty("codebridge.test.fixtures", rootProject.file("src/test-extensions").absolutePath)
    }
}

/** `runIde` always starts an extension host, so it always unpacks the runtime first. */
tasks.withType<RunIdeTask>().configureEach {
    dependsOn(":runtime:extractHostRuntime")
    usesHostRuntime()
}

/**
 * Gives the client started by `runIdeFrontend`, or by an alternative IDE's `run<Name>Frontend`, a
 * properties file of its own.
 *
 * The Gradle plugin points every split-mode client at one `frontend.properties` in the sandbox, and
 * each `prepareSandbox_*` task that runs rewrites it with its own paths. The *(Split Mode)* compounds
 * run the backend and frontend tasks as two Gradle invocations, so the backend's rewrite can land
 * last: the client then reads the backend's frontend plugin directory, which holds nothing, and starts
 * without CodeBridge. The task's own sandbox directories are the frontend's, so they are written here,
 * and the JVM takes this `-Didea.properties.file` over the plugin's because it comes later.
 */
(listOf("runIdeFrontend") + alternativeIdes.keys.map { "run${it.name}Frontend" }).forEach { frontend ->
    tasks.named<RunIdeTask>(frontend) {
        val properties = layout.buildDirectory.file("$frontend/frontend.properties")
        val directories = mapOf(
            "idea.config.path" to sandboxConfigDirectory,
            "idea.system.path" to sandboxSystemDirectory,
            "idea.log.path" to sandboxLogDirectory,
            "idea.plugins.path" to sandboxPluginsDirectory,
        )
        doFirst {
            properties.get().asFile.apply { parentFile.mkdirs() }.writeText(
                directories.entries.joinToString("\n") { (key, directory) ->
                    "$key=${directory.get().asFile.invariantSeparatorsPath}"
                }
            )
        }
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Didea.properties.file=${properties.get().asFile.invariantSeparatorsPath}")
        })
    }
}

/** Syntax-only fixture: no extension host or npm build is needed to exercise TextMate. */
tasks.register<Zip>("buildTextMateFixture") {
    group = "distribution"
    description = "Packages the syntax-only TextMate fixture as a VSIX."
    archiveFileName.set("codebridge-textmate-probe.vsix")
    destinationDirectory.set(layout.buildDirectory.dir("fixtures"))
    from("src/test-extensions/textmate-extension") {
        into("extension")
        exclude("README.md")
    }
}

tasks.register<Zip>("buildWorkbenchFixture") {
    group = "distribution"
    description = "Packages the decorations and diff probe the split-mode workbench test drives, as a VSIX."
    archiveFileName.set("codebridge-workbench-probe.vsix")
    destinationDirectory.set(layout.buildDirectory.dir("fixtures"))
    from("src/test-extensions/workbench-probe-extension") {
        into("extension")
        exclude("README.md")
    }
}

/**
 * Packages the bundled fixture so language support can be exercised in a real IDE.
 *
 * It is deliberately the *bundled* fixture: one `dist/extension.js` with no `node_modules`, which is
 * what a published extension installs as. `src/` and the lockfile are build inputs, not part of it.
 */
val lspFixture = layout.projectDirectory.dir("src/test-extensions/exthost/lsp-bundled-extension")
tasks.register<Zip>("buildLspFixture") {
    val bundle = lspFixture.file("dist/extension.js").asFile
    group = "distribution"
    description = "Packages the bundled hover/diagnostics fixture as a VSIX for manual testing."
    archiveFileName.set("codebridge-lsp-probe.vsix")
    destinationDirectory.set(layout.buildDirectory.dir("fixtures"))
    from(lspFixture) {
        into("extension")
        exclude("src/**", "node_modules/**", "package-lock.json")
    }
    doFirst {
        check(bundle.isFile) {
            "Run npm install && npm run build in src/test-extensions/exthost/lsp-bundled-extension, then delete node_modules"
        }
    }
}

/** The LSP fixture as a plugin of its own, see `src/test-extensions/bundling-plugin`. */
val bundlingPluginJar = tasks.register<Jar>("bundlingPluginJar") {
    description = "Packages the bundling-plugin fixture's descriptor as a plugin jar."
    archiveFileName.set("bundling-probe.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tmp/bundling-plugin"))
    from("src/test-extensions/bundling-plugin/plugin.xml") { into("META-INF") }
}
tasks.register<Sync>("buildBundlingPluginFixture") {
    group = "distribution"
    description = "Lays out the LSP fixture as an IntelliJ plugin that bundles it, for the split-mode tests."
    into(layout.buildDirectory.dir("fixtures/bundling-plugin"))
    from(bundlingPluginJar) { into("lib") }
    from(lspFixture) {
        into("extension")
        exclude("src/**", "node_modules/**", "package-lock.json")
    }
}
