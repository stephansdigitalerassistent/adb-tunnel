plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signed with one fixed key from CI secrets. AGP's default debug key differs per machine, so every
// build would refuse to install over the last — and the only way past that, uninstalling, deletes
// the app's SSH key, which then has to be authorized on the server all over again.
val signingStorePath: String? = System.getenv("SIGNING_STORE_FILE")
val hasSigningKey = signingStorePath != null && file(signingStorePath).exists()

// Counted, so a newer build always has the higher version code. Needs full history in CI.
fun gitCommitCount(): Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.toIntOrNull() ?: 0 else 0
} catch (e: Exception) {
    0
}

val appVersionCode = 2 + gitCommitCount()

android {
    namespace = "ch.heuscher.adbtunnel"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.heuscher.adbtunnel"
        // Wireless debugging, which this app exists to reach, is Android 11.
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "1.1.$appVersionCode"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("stable") {
                storeFile = file(signingStorePath!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (hasSigningKey) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // JSch is a multi-release jar; the per-Java-version copies only duplicate classes D8
            // already has from the base layer.
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.7")
}
