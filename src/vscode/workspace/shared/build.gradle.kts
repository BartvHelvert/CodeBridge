/** The RPC the VS Code workspace module's two halves talk over. */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.rpc")
    }
    implementation(project(":shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
}
