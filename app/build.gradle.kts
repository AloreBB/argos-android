plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version comes from the git tag in CI (-PversionName=1.2.3); local builds use a dev name.
val appVersionName = (findProperty("versionName") as String?) ?: "0.0.0-dev"
val appVersionCode = appVersionName.substringBefore('-').split('.')
    .mapNotNull { it.toIntOrNull() }
    .let { parts -> (parts + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> maxOf(1, major * 10_000 + minor * 100 + patch) }

// Release signing from env (CI secrets). Without it, release builds fall back to the debug key so
// `assembleRelease` still works locally; CI refuses to publish in that case.
val releaseKeystore = System.getenv("ARGOS_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "dev.alore.argos"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.alore.argos"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ARGOS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARGOS_KEY_ALIAS")
                keyPassword = System.getenv("ARGOS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // sshj + BouncyCastle rely on reflection; shrinking buys little for a personal app.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
            )
            // BouncyCastle's jars each ship the same license files.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE", "META-INF/NOTICE")
        }
    }

    testOptions {
        // android.util.Log & co. return defaults in JVM unit tests.
        unitTests.isReturnDefaultValues = true
        // Robolectric UI tests render real resources/Compose on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":terminal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Robolectric pokes JDK internals (FileDescriptor) on modern JDKs.
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}
