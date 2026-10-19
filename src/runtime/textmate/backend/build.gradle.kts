import org.jetbrains.intellij.platform.gradle.TestFrameworkType

dependencies {
    intellijPlatform {
        bundledPlugin("org.jetbrains.plugins.textmate")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
    implementation(project(":backend"))
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("idea.load.plugins.id", "me.helvert.codebridge,org.jetbrains.plugins.textmate")
    dependsOn(":buildTextMateFixture")
    systemProperty(
        "codebridge.textmate.vsix",
        rootProject.layout.buildDirectory.file("fixtures/codebridge-textmate-probe.vsix").get().asFile.absolutePath
    )
}
