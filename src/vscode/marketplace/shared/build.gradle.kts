/**
 * Open VSX's data model and the RPC the marketplace's two halves talk over, kept apart from the
 * core so browsing and installing from a registry is a module of its own.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.rpc")
    }

    implementation(project(":shared"))

    compileOnly(libs.kotlin.serialization.json.jvm)

    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
