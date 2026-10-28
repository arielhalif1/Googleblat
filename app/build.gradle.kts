import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties (git-ignored) points at the key.
// The key must stay the same forever, or Android refuses to update the store.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.googleblat.store"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.googleblat.store"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField(
            "String", "CATALOG_URL",
            "\"https://raw.githubusercontent.com/arielhalif1/Googleblat/main/catalog.json\""
        )
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Bundle catalog.json as a fallback for when GitHub can't be reached.
val catalogAssets = layout.buildDirectory.dir("generated/catalogAssets")
val copyCatalog by tasks.registering(Copy::class) {
    from(rootProject.file("catalog.json"))
    into(catalogAssets)
}
android.sourceSets["main"].assets.srcDir(catalogAssets)
tasks.named("preBuild") { dependsOn(copyCatalog) }
