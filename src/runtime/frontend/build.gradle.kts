import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
        // The debugger's frames list and its frames, for what extensions add to the Call Stack menu.
        bundledModule("intellij.platform.debugger")
        bundledModule("intellij.platform.debugger.impl.ui")
    }

    implementation(project(":shared"))
    // `Urls`, for the path of the links into the editor served here.
    implementation(project(":protocol"))
    testImplementation(libs.kotlin.serialization.json.jvm)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

/**
 * Stamps each build with the time it was produced.
 *
 * The plugin's settings pages show this, so it is possible to tell at a glance whether a running
 * IDE has picked up the build you just made. A running IDE keeps its plugin classes in memory, and
 * without a visible stamp a stale instance is indistinguishable from a change that did not work.
 *
 * Deliberately never up to date: the value is the wall clock, so caching it would defeat it.
 */
val generateBuildStamp = tasks.register("generateBuildStamp") {
    description = "Stamps the build with the time it was produced, for the settings pages to show."
    val outputDir = layout.buildDirectory.dir("generated/resources/stamp")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    doLast {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val file = outputDir.get().file("codebridge-build-stamp.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(stamp)
    }
}

sourceSets["main"].resources.srcDir(generateBuildStamp)
