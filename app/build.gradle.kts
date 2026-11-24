import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")

    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val posthogToken = localProperties
    .getProperty("POSTHOG_PROJECT_TOKEN", "")
    .trim()

android {
    namespace = "com.paveltyapkin.phrasoteca"
    compileSdk {
        version = release(37)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.paveltyapkin.phrasoteca"
        minSdk = 29
        targetSdk = 37
        versionCode = 69
        versionName = "0.87.0-alpha5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "POSTHOG_PROJECT_TOKEN",
            "\"$posthogToken\""
        )
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "POSTHOG_PROJECT_TOKEN",
                "\"\""
            )
        }
        release {
            optimization {
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    val roomVersion = "2.8.5"

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.posthog:posthog-android:4.+")
}


