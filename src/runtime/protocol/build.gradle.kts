/**
 * VS Code's extension host protocol, on its own.
 *
 * Two halves, in two packages. `protocol` is the envelope: frames, RPC messages and the rpcId
 * table, which is bytes on one side and messages on the other. `protocol.payload` is what those
 * messages carry: the JSON shapes the host and the workbench exchange, most of which have no
 * published description and were read off the runtime bundle.
 *
 * Nothing here knows about IntelliJ, VS Code installations or extension manifests. That is what
 * keeps it testable against captured traffic with no runtime on disk, and it is the rule to apply
 * when deciding whether something belongs here or in `shared`: a dependency on the platform, on an
 * installation or on a manifest means it does not.
 */
dependencies {
    // The IntelliJ Platform ships kotlinx-serialization-json at runtime, so it must not be bundled.
    compileOnly(libs.kotlin.serialization.json.jvm)

    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
