/** Reading the VS Code extensions other plugins ship, for the runtime to run. */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.backend")
    }
    implementation(project(":backend"))
    implementation(project(":shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
}
