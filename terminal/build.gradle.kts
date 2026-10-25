// Vendored Termux terminal-emulator + terminal-view (Apache 2.0), see NOTICE.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Upstream code; we don't own its lint debt.
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.annotation)
}
