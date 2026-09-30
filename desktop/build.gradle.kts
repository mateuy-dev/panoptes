plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

group = "dev.mateuy"
version = rootProject.version

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Domain, store adapters and infrastructure
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
}

// The app runs from this module's folder, so run.sh names the project to open
tasks.withType<JavaExec>().configureEach {
    providers.environmentVariable("PANOPTES_PROJECT").orNull?.let { environment("PANOPTES_PROJECT", it) }
}

// `./gradlew :desktop:run`; `./gradlew :desktop:packageDeb` (or Dmg/Msi) builds an installer
compose.desktop {
    application {
        mainClass = "dev.mateuy.panoptes.desktop.DesktopMainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
            )
            packageName = "Panoptes"
            packageVersion = project.version.toString()
        }
    }
}
