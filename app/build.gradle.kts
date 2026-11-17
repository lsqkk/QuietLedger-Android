plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val healthFallback = providers.gradleProperty("quietLedgerHealthFallback").orElse("")

android {
    namespace = "dev.quietledger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.quietledger.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.9.4"
        buildConfigField("String", "API_BASE", "\"https://ql.130923.xyz\"")
        val fallback = healthFallback.get()
        require(fallback.isEmpty() || fallback.matches(Regex("https?://[a-zA-Z0-9.:-]+(?:/[a-zA-Z0-9/_-]*)?"))) {
            "quietLedgerHealthFallback must be a base URL without query parameters"
        }
        buildConfigField("String", "HEALTH_FALLBACK", "\"$fallback\"")
        manifestPlaceholders["allowCleartextHealthFallback"] = fallback.startsWith("http://")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
