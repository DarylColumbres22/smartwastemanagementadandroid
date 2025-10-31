plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.smartwaste.capstone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartwaste.capstone"
        minSdk = 25
        targetSdk = 34
        versionCode = 12
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField ("String", "SENDGRID_KEY", "\"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\"")


    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Firebase BOM (Bill of Materials)
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-functions-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    // coroutines for .await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    coreLibraryDesugaring ("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation ("com.facebook.shimmer:shimmer:0.5.0")

    implementation ("com.github.PhilJay:MPAndroidChart:3.1.0")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation ("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")




    implementation ("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:20.6.0")
    implementation ("com.google.firebase:firebase-firestore:24.7.1")

    implementation ("androidx.core:core-ktx:1.13.1")

    implementation ("com.google.android.material:material:1.12.0")





    implementation ("com.google.firebase:firebase-auth:22.0.0")
    implementation ("com.google.firebase:firebase-firestore:24.0.0")
    implementation ("com.android.volley:volley:1.2.1")



    // AndroidX Libraries
    implementation("androidx.core:core-ktx:1.12.0")
    implementation ("com.google.firebase:firebase-auth-ktx")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")


    // Material Design
    implementation("com.google.android.material:material:1.9.0")
    implementation("com.github.lzyzsd:circleprogress:1.2.1")

    implementation ("androidx.gridlayout:gridlayout:1.0.0")
    implementation ("com.mikhaellopez:circularprogressbar:3.1.0")
    implementation ("com.google.firebase:firebase-messaging:23.1.1")


    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")

    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")



    implementation ("de.hdodenhof:circleimageview:3.1.0")


    implementation ("com.google.code.gson:gson:2.8.8")

    implementation ("com.squareup.okhttp3:okhttp:4.9.0")




    implementation ("com.github.bumptech.glide:glide:4.12.0")
    implementation("com.google.firebase:firebase-storage-ktx:21.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation ("com.google.android.gms:play-services-safetynet:18.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.datatransport:transport-api:3.0.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
