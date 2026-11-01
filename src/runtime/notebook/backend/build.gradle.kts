/**
 * Notebooks on the backend: the extension host's notebook services, and every open notebook,
 * served to whichever frontend draws it. A module of its own, beside the editor in
 * `notebook.frontend`, so notebooks come and go as one feature.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledModule("intellij.platform.project")
    }
    implementation(project(":backend"))
    implementation(project(":protocol"))
    implementation(project(":shared"))
    implementation(project(":notebook.shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
