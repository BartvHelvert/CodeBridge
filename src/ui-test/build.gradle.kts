import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import java.nio.file.Path
import java.time.Duration

/**
 * Split-mode validation, and nothing else.
 *
 * This module builds no plugin code. It launches a real IDE backend and a real JetBrains Client with
 * the plugin installed in both, and asks the *client* what it drew -- which is the one question none
 * of the other suites can answer, because a `CodeInsightTestFixture` is a single in-process IDE with
 * no client to forward anything to.
 *
 * Run only when asked for by name: every test here boots two IDEs and downloads them the first
 * time, so it is minutes rather than seconds, and `gradlew test`, `check` or `build` should not
 * start any of that. `gradlew :ui-test:test`, or Run on a test in the IDE, does.
 */
plugins {
    id("org.jetbrains.intellij.platform.module")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    intellijPlatform {
        // Launches IDEs and drives them; the Driver half is what reads the client's UI model back.
        testFramework(TestFrameworkType.Starter)
    }

    testImplementation(kotlin("test"))
    // Prepare the isolated backend store using the same VSIX installer as the plugin.
    testImplementation(project(":backend"))
    testImplementation(project(":shared"))
    testRuntimeOnly(libs.kotlin.serialization.json.jvm)
    // The same JUnit as every other module: the platform's test dependencies bring JUnit 6 engines,
    // which an older launcher cannot run.
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

// This agent belongs only to the test launches; it is never included in the plugin distribution.
val windowAgent = sourceSets.create("windowAgent")
val windowAgentTest = sourceSets.create("windowAgentTest") {
    compileClasspath += windowAgent.output
    runtimeClasspath += windowAgent.output
}
val windowAgentJar = tasks.register<Jar>("windowAgentJar") {
    description = "Packages the agent that places the test IDE's frame before it is shown."
    archiveClassifier.set("window-agent")
    from(windowAgent.output)
    manifest.attributes("Premain-Class" to "me.helvert.codebridge.uitest.WindowPlacementAgent")
}
tasks.register<JavaExec>("verifyWindowAgent") {
    description = "Checks frame placement before visibility without launching an IDE or AWT window."
    classpath = windowAgentTest.runtimeClasspath
    dependsOn(windowAgentJar)
    jvmArgs("-javaagent:${windowAgentJar.flatMap { it.archiveFile }.get().asFile.absolutePath}=-1600,200,1400,1000")
    mainClass.set("me.helvert.codebridge.uitest.WindowPlacementAgentCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    providers.gradleProperty("codebridge.window.agent.ideJar").orNull?.let { args(it) }
}

/**
 * What the test installs into both sandboxes: the plugin as the root project's test sandbox lays it
 * out. The extension host is not in it; the backend is pointed at the one `extractHostRuntime`
 * unpacks, as `runIde` is.
 *
 * Not the `buildPluginVariants_<host>` ZIP: that compresses the whole runtime on every code change,
 * only for Starter to unpack it again into each IDE. The distribution's own packaging is not what
 * these tests are about.
 */
val testSandbox = rootProject.tasks.named<PrepareSandboxTask>("prepareTestSandbox")
val pluginDirectory = testSandbox.flatMap { it.pluginDirectory }
val bundledDirectory = project(":bundled").tasks.named<PrepareSandboxTask>("prepareTestSandbox").flatMap { it.pluginDirectory }

/** What the test opens. */
val probeVsix = rootProject.layout.buildDirectory.file("fixtures/codebridge-lsp-probe.vsix")

/**
 * Whether this build names the UI tests itself -- `gradlew :ui-test:test`, or the IDE running one of
 * them -- rather than reaching them through `test`, `check` or `build` in every module.
 *
 * Not a task of their own: the IntelliJ Platform plugin gives `test` its classpath, runtime and class
 * loader in internal code, which a second `Test` task does not get.
 */
val testTaskPath = "$path:test"
val requestedByName = gradle.startParameter.taskNames.any { it == testTaskPath || it == testTaskPath.removePrefix(":") }

tasks.test {
    useJUnitPlatform()
    dependsOn(windowAgentJar)
    systemProperty("codebridge.window.agent", windowAgentJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    // The plugin has to exist before it can be installed into an IDE.
    dependsOn(testSandbox, ":runtime:extractHostRuntime", ":buildLspFixture", ":bundled:prepareTestSandbox")
    dependsOn(":buildTextMateFixture", ":buildWorkbenchFixture", ":buildBundlingPluginFixture")
    systemProperty(
        "codebridge.bundling.plugin",
        rootProject.layout.buildDirectory.dir("fixtures/bundling-plugin").get().asFile.absolutePath
    )
    systemProperty(
        "codebridge.workbench.vsix",
        rootProject.layout.buildDirectory.file("fixtures/codebridge-workbench-probe.vsix").get().asFile.absolutePath
    )
    systemProperty(
        "codebridge.textmate.vsix",
        rootProject.layout.buildDirectory.file("fixtures/codebridge-textmate-probe.vsix").get().asFile.absolutePath
    )
    systemProperty("codebridge.plugin.dir", pluginDirectory.get().asFile.absolutePath)
    // The runtime, which the sandbox lays out beside CodeBridge as the plugin it depends on.
    systemProperty("codebridge.runtime.plugin.dir", pluginDirectory.get().asFile.resolveSibling("CodeBridgeRuntime").absolutePath)
    // CodeBridge Bundled Extensions, which CodeBridge does not depend on: from its own sandbox.
    systemProperty("codebridge.bundled.plugin.dir", bundledDirectory.get().asFile.absolutePath)
    systemProperty("codebridge.probe.vsix", probeVsix.get().asFile.absolutePath)
    // The IDE the plugins are built against. Without it Starter downloads the newest 2026.3 build,
    // which changes under us and is not the one the Plugin Verifier checked.
    systemProperty("codebridge.ide.build", libs.versions.idea.get())
    // Where Starter keeps the IDE installers it downloads: in the cache every checkout shares (see
    // the root build script), beside the IDEs the build unpacks.
    val sharedCacheDirectory: File by rootProject.extra
    systemProperty("codebridge.ide.installers", sharedCacheDirectory.resolve("installers").absolutePath)
    systemProperty("codebridge.test.work", rootProject.layout.projectDirectory.dir("out").asFile.absolutePath)
    // The tests' projects by their real paths. On the Windows CI runner the temporary directory is a
    // short name, `C:\Users\RUNNER~1\...`, which the IDEs resolve to `runneradmin`: a file a test
    // named by the short form was not the one in the client's editor, and nothing drawn for it showed.
    systemProperty("java.io.tmpdir", Path.of(System.getProperty("java.io.tmpdir")).toRealPath().toString())
    // Two IDEs per test, both downloaded on the first run, and each launch indexes its project
    // before the test starts: minutes apiece on a two-core CI runner. Inside the CI job's two hours.
    timeout.set(Duration.ofMinutes(110))
    // A local copy: the configuration cache cannot keep a reference to the script itself.
    val requested = requestedByName
    onlyIf("the UI tests run only when :ui-test:test is asked for by name") { requested }
}
