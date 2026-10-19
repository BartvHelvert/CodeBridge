/** Open VSX on the extension host machine: searching it, and downloading what the user installs. */
dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.rpc.backend")
    }

    implementation(project(":backend"))
    implementation(project(":shared"))
    implementation(project(":marketplace.shared"))
    compileOnly(libs.kotlin.serialization.json.jvm)
}

dependencies {
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
