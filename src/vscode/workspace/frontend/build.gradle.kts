/** Opening the VS Code workspace files, and suggesting a `.code-workspace` file the project has. */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
    }
    implementation(project(":frontend"))
    implementation(project(":shared"))
    implementation(project(":workspace.shared"))
}
