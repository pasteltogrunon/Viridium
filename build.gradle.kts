
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Opt-in client integration test. The diagnostic mod is never included in a normal build.
if (providers.gradleProperty("treePreviewSmokeTest").isPresent) {
    sourceSets.named("main") {
        java.srcDir("src/previewTest/java")
    }
}
