import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.dap")
        // The DAP protocol types `intellij.platform.dap`'s API is written in, from 2026.3. Compile
        // only: the module is internal to JetBrains' namespace, so declaring it in
        // CodeBridge.backend.xml would stop the module loading. Its classes still resolve at
        // runtime through `intellij.platform.dap`, since plugin classloaders search parents
        // transitively.
        bundledModule("intellij.platform.dap.protocol")
        // `com.intellij.xdebugger.*`, which the DAP breakpoint types extend. Part of the core
        // classpath until 2026.2, a content module of its own from 2026.3.
        bundledModule("intellij.platform.debugger")
        // Run | Attach to Process (`XAttachDebuggerProvider`).
        bundledModule("intellij.platform.debugger.impl")

        // lsp4j's DAP types, for the fake debug adapter the adapter-handle test speaks to; the plugin
        // itself uses the platform's own protocol types from 2026.3.
        testBundledModule("intellij.libraries.eclipse.lsp4j.debug")
        // `InMemoryCredentialStore`, the KeePass-backed store the hosted-secrets test writes to.
        // Part of the core classpath until 263.6259, a content module of its own from 263.7031.
        testBundledModule("intellij.platform.credentialStore.impl")

        // Lets backend tests open a real editor and ask what the daemon highlighted, which is the
        // only way to cover the annotator, the hover target and the file type detector.
        testFramework(TestFrameworkType.Platform)
        // The JUnit 5 fixtures, so these tests are written like every other test in the project
        // rather than as JUnit 3 TestCase subclasses.
        testFramework(TestFrameworkType.JUnit5)
        // `LocalProcessService`, which `RunInTerminal`'s pseudo-terminals need; see `tasks.test`.
        testBundledPlugin("com.intellij.modules.pty4j")
    }

    implementation(project(":protocol"))
    implementation(project(":shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    // Renders hovers. Bundled: the IDE ships the same parser, but in modules plugins may not use.
    implementation(libs.markdown) {
        exclude(group = "org.jetbrains.kotlin")
    }
    // The breadcrumbs collector, in `:internal.backend` for its internal API, is tested here beside the
    // fixture every editor test shares.
    testImplementation(project(":internal.backend"))
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnitPlatform()
    // Load only this plugin. A light fixture otherwise starts every bundled plugin, and several
    // cannot initialise in one -- Vue's LSP service raises NoClassDefFoundError from a background
    // thread, which the fixture reports as an uncaught exception and fails whichever test was
    // running. The exceptions: pseudo-terminals, a plugin of their own from 2026.3 (with the JNA
    // one it needs), without which a `PtyCommandLine` falls back to pipes and a process handler
    // cannot be destroyed.
    systemProperty("idea.load.plugins.id", "me.helvert.codebridge.runtime,com.intellij.modules.pty4j,com.intellij.modules.jna")
}
