plugins { id("com.android.application") }

android {
    namespace = "com.smfworks.spoteyes"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.smfworks.spoteyes"
        minSdk = 22
        targetSdk = 22
        versionCode = 13
        versionName = "1.3.1"
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
