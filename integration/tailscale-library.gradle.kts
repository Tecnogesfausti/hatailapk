plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tail = extensions.getByType<VersionCatalogsExtension>().named("tailscaleLibs")
android {
    namespace = "com.tailscale.ipn"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "USE_GOOGLE_DNS_FALLBACK", "true")
        buildConfigField("String", "VERSION_NAME", "\"HATailAPK development\"")
        buildConfigField("String", "APPLICATION_ID", "\"es.tecnogesfausti.hatailapk.minimal.debug\"")
        manifestPlaceholders["leanbackRequired"] = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    buildFeatures { buildConfig = true; compose = true }
}

dependencies {
    listOf(
        "androidx-fragment", "androidx-core", "androidx-coreKtx", "androidx-browser",
        "androidx-security-crypto", "androidx-work-runtime", "androidx-work-runtimeKtx",
        "kotlinx-serialization-json", "kotlinx-coroutines-core", "androidx-room-ktx",
        "kotlin-stdlib", "kotlin-reflect", "androidx-compose-material3",
        "androidx-compose-material-icons", "androidx-compose-ui", "androidx-compose-ui-toolingPreview",
        "androidx-lifecycle-viewmodel-ktx", "androidx-activity-compose", "accompanist-permissions",
        "accompanist-systemuicontroller", "androidx-core-splashscreen", "androidx-compose-animation",
        "androidx-navigation-compose", "androidx-navigation-ui", "coil-compose", "zxing-core",
        "vico-compose", "vico-composeM3",
    ).forEach { implementation(tail.findLibrary(it).get()) }
    implementation(platform(tail.findLibrary("androidx-compose-bom").get()))
    runtimeOnly(tail.findLibrary("kotlinx-coroutines-android").get())
    compileOnly(files(rootProject.file("../../upstream/tailscale-android/android/libs/libtailscale.aar")))
}
