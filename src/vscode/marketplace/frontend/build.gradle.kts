/** The VS Code Extensions settings page: Open VSX's marketplace, and the extensions installed from it. */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
    }

    implementation(project(":frontend"))
    implementation(project(":shared"))
    implementation(project(":marketplace.shared"))
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
