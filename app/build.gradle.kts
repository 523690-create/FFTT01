import java.text.SimpleDateFormat
import java.util.Date
import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.fftt01"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.fftt01"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Rename and copy APK to project root after build
val rootPathStr = project.rootDir.absolutePath
tasks.withType<com.android.build.gradle.tasks.PackageApplication>().configureEach {
    val captureRoot = rootPathStr
    doLast {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val fileName = "FFTT01-$timestamp.apk"
        outputDirectory.get().asFile.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk")) {
                val destFile = File(captureRoot, fileName)
                file.copyTo(destFile, overwrite = true)
                println("Generated APK in root: ${destFile.absolutePath}")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}