plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linfranca.ytdlpmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linfranca.ytdlpmobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 56
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// The paired recorder invokes FFmpeg's native executable, which needs the
// Android NDK shared C++ runtime. Copy it directly into the APK's JNI libs;
// generating a CMake/Ninja project just for this runtime is unnecessary.
val cppRuntimeDir = layout.buildDirectory.dir("generated/cppRuntimeJniLibs")
val packageCppRuntime by tasks.registering(Copy::class) {
    val prebuilt = android.ndkDirectory.resolve("toolchains/llvm/prebuilt")
        .listFiles()?.firstOrNull { it.isDirectory && it.resolve("sysroot/usr/lib").isDirectory }
        ?: throw GradleException("Install the Android NDK through Android Studio SDK Manager.")
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "arm-linux-androideabi",
        "x86_64" to "x86_64-linux-android"
    ).forEach { (abi, target) ->
        val runtime = prebuilt.resolve("sysroot/usr/lib/$target/libc++_shared.so")
        if (!runtime.isFile) throw GradleException("Android NDK runtime missing: $runtime")
        from(runtime) { into(abi) }
    }
    into(cppRuntimeDir)
}
// Give Gradle the producing task, not just its directory path. AGP's
// merge<Variant>JniLibFolders reads this generated JNI source directory.
android.sourceSets.getByName("main").jniLibs.srcDir(packageCppRuntime)
tasks.matching {
    it.name.startsWith("merge") &&
        (it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs"))
}
    .configureEach { dependsOn(packageCppRuntime) }

dependencies {
    val youtubedlAndroid = "0.18.1"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.webkit:webkit:1.15.0")

    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    testImplementation("junit:junit:4.13.2")
}
