plugins { id("com.android.library") }

android {
    namespace = "at.techtown.sonoscontroller"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation("androidx.core:core:1.18.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
