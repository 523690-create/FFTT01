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
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

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
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
            output.outputFileName.set("FFTT01-$timestamp.apk")
        }
    }
}

tasks.register("copyApkToRoot") {
    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
    val rootDir = layout.projectDirectory.dir("..")
    doLast {
        val apkFolder = apkDir.get().asFile
        val targetFolder = rootDir.asFile
        apkFolder.listFiles()?.filter { it.name.startsWith("FFTT01-") && it.extension == "apk" }?.forEach { file ->
            file.copyTo(File(targetFolder, file.name), overwrite = true)
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy("copyApkToRoot")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
