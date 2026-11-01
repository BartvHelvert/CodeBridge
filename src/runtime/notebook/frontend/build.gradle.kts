/**
 * The notebook editor: a file an extension's notebook type claims opens as its cells, drawn with
 * the IDE's own editors, run by the kernel an extension provides. The notebook itself is kept by
 * `notebook.backend`.
 */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
        bundledModule("intellij.platform.project")
    }
    implementation(project(":notebook.shared"))
    // How a completion is drawn and inserted, the same in a cell as in a file.
    implementation(project(":shared"))
    // Renders markdown cells. Not embedded: the IDE's core class loader has the same parser.
    implementation(libs.markdown) {
        exclude(group = "org.jetbrains.kotlin")
    }
    compileOnly(libs.kotlin.serialization.json.jvm)
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
