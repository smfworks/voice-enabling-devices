plugins { id("com.android.application") }

android {
    namespace = "com.smfworks.jamesvoice"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.smfworks.jamesvoice"
        minSdk = 22
        targetSdk = 22
        versionCode = 8
        versionName = "1.4.1"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi", "GradleDependency")
    }
}
