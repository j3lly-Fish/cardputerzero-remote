import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key: a keystore.properties file (storeFile, storePassword, keyAlias,
// keyPassword) outside the repo, given by CPZ_SIGNING_PROPERTIES. See make-signing-key.sh.
val signingProps = System.getenv("CPZ_SIGNING_PROPERTIES")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "cc.cpz.remote"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.cpz.remote"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (signingProps != null) {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = signingProps.parentFile.resolve(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
