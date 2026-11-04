plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
}

android {
    namespace = "com.vi.pl.videoplayerdemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hyperideo.hdvideoplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // F02：mapping 上传开关显式声明，供 rg 静态核对（需求 §F02「按各工程决定」）。
    // 决策：release 上传 mapping 用于还原混淆栈，Debug 不需要；线上包本地不编译，
    // 所以这一项只作静态口径，实际产物由构建流水线决定。
    firebaseCrashlytics {
        mappingFileUploadEnabled = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.work:work-runtime-ktx:2.10.4")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    annotationProcessor("androidx.room:room-compiler:2.7.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)


    implementation("com.adjust.sdk:adjust-android:5.6.1")

    implementation(platform(libs.firebase.bom))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-config")



    implementation("com.flux.tracksdk:tracksdk:3.0.3")

    implementation("com.android.installreferrer:installreferrer:2.2")

    implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
    implementation("com.google.android.gms:play-services-ads:25.2.0")

    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    implementation("com.google.android.play:review-ktx:2.0.2")
}
