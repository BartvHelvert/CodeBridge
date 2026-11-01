/**
 * The RPC notebooks' two halves talk over: the backend keeps each notebook and speaks to the
 * extension host about it, the frontend draws it. A module of its own so both halves can be left
 * out together.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.rpc")
        bundledModule("intellij.platform.project")
    }

    compileOnly(libs.kotlin.serialization.json.jvm)

    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
