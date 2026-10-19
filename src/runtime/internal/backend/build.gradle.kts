/**
 * What CodeBridge can only do through the platform's internal API, kept apart so a published build
 * can leave it out (`-PinternalApi=false`) and pass the Plugin Verifier's internal API check. What
 * a build without it loses is listed in `docs/platform-limitations.md`.
 *
 * Its tests are in `:backend`, beside the fixture they share.
 */
dependencies {
    implementation(project(":backend"))
}
