import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

val appProps = Properties().apply {
    load(FileInputStream(rootProject.file("app.properties")))
}
val appName: String = appProps.getProperty("app.name")
val appPackage: String = appProps.getProperty("app.package")

// versionCode/versionName are normally passed in from CI as project properties
// (-PappVersionCode=... -PappVersionName=...), derived from the run number and
// release tag respectively. Falls back to app.properties for local/dev builds
// where nobody passes those flags — versionCode 1 is fine locally since it's
// never uploaded anywhere.
val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
val ciVersionName = project.findProperty("appVersionName") as String?

android {
    namespace = appPackage
    compileSdk = 36

    defaultConfig {
        applicationId = appPackage
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: error("appVersionName must be set via -PappVersionName")
        resValue("string", "app_name", appName)

        manifestPlaceholders["appAuthRedirectScheme"] = appPackage
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.create("release") {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "OSGI-INF/**",
                "META-INF/ECLIPSE_.*",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "about.html",
                "plugin.properties"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.jgit)
    implementation(libs.jgit.http.apache)

    implementation(libs.appauth)

    implementation(libs.androidx.security.crypto)
}
