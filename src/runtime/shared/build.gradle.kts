/**
 * Libraries packed into this module's own jar. A content module's classloader sees its jar and the
 * modules it depends on, not the plugin's `lib/` -- that is the main plugin classloader's -- so a
 * library only this module uses has to travel inside it.
 */
val embedded: Configuration = configurations.create("embedded")

kotlin {
    // The Windows transport uses the finalized Foreign Function & Memory API (JDK 22+).
    // Match the JBR 25 runtime of the targeted IntelliJ build.
    jvmToolchain(25)
}

dependencies {
    intellijPlatform {
        // `RemoteApiProviderService`. Part of the core classpath until 2026.2; from 2026.3 it is a
        // content module of its own, so both the compiler and the loader have to be told about it.
        bundledModule("intellij.platform.rpc")
        // `ProjectId`, which is how a project-scoped RPC names its project on both sides. A content
        // module in 2026.3 for the same reason as the one above.
        bundledModule("intellij.platform.project")
    }

    implementation(project(":protocol"))
    // Unpacks WOFF2 icon fonts extensions contribute (`IconFonts`). The IDE ships no Brotli decoder.
    embedded(libs.brotli.dec)
    compileOnly(libs.brotli.dec)
    testImplementation(libs.brotli.dec)

    // The IntelliJ Platform ships kotlinx-serialization-json at runtime, so it must not be bundled.
    compileOnly(libs.kotlin.serialization.json.jvm)

    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jar {
    from(embedded.map { zipTree(it) }) {
        exclude("META-INF/**")
    }
}
