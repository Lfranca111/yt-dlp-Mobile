# Building yt-dlp Mobile

This guide describes the Android Studio project and native ARM64 overlay used for the 1.0.0 release preparation. Use the checked-in Gradle configuration as the authority for exact versions.

## Required project files

The repository must contain the complete project, not only a changed-files ZIP:

```text
app/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/java/
  src/main/res/
  src/main/cpp/
  src/main/jniLibs/arm64-v8a/libmedia_arm64.so
gradle/wrapper/
gradlew
gradlew.bat
build.gradle.kts
settings.gradle.kts
gradle.properties
tools/build_arm64.py
tools/verify_arm64.py
```

Keep the Gradle wrapper JAR and properties file. Install the documentation ZIP at this root so `README.md` sits beside `app/`.

## Build environment

- Android Studio with the SDK components required by the project.
- JDK 17 for the checked Gradle/Android plugin configuration; configure the Gradle JDK accordingly.
- Android SDK Platform 35 for the documented `compileSdk`/`targetSdk`.
- Android NDK: the current app Gradle task uses its shared C++ runtime for FFmpeg packaging.
- Python 3 if rebuilding or verifying the custom ARM64 library.
- Internet access to resolve Gradle dependencies and download the wrapper distribution on the first build.

The configuration reviewed during documentation preparation uses Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin plugin 2.1.0, minimum SDK 29, and supported ABI filters `arm64-v8a`, `armeabi-v7a`, and `x86_64`. Do not overwrite a newer working configuration solely to match this list.

The ARM64 overlay itself ships a prebuilt library and does not introduce an NDK requirement for that binary. The full app's existing C++ runtime packaging task does require the NDK.

## Open and build

1. Open the project root in Android Studio.
2. Install missing SDK/NDK components using SDK Manager.
3. Allow Gradle sync to finish.
4. Connect an Android 10+ device or use a suitable emulator.
5. Build and run a debug APK first.

From the root, on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

On Linux/macOS:

```bash
chmod +x gradlew
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Unit tests do not substitute for WebView login, recording, SAF file access, or PDF attachment tests on a device.

## Version and launcher icon

In `app/build.gradle.kts`, the planned 1.0.0 metadata is:

```kotlin
versionCode = 57
versionName = "1.0.0"
```

If you already installed an APK with a higher code, use a code higher than that one. The displayed version and monotonically increasing Android version code serve different purposes.

Inside `<application>` in `app/src/main/AndroidManifest.xml`, use the generated launcher resources:

```xml
android:icon="@mipmap/ic_launcher"
android:roundIcon="@mipmap/ic_launcher_round"
```

Keep `ic_download.xml` if buttons or notifications use it. The new icon resources belong in `app/src/main/res/mipmap-*` and their referenced drawable resources.

## ARM64 assembly library

The custom library is packaged from:

```text
app/src/main/jniLibs/arm64-v8a/libmedia_arm64.so
```

Its Kotlin adapter is `NativeMedia.kt`, its C bridge is `media_jni.c`, and its assembly files are under `app/src/main/cpp`.

Normal Gradle builds package the prebuilt `.so`; they do **not** automatically recompile the assembly. After changing `.S`, bridge, or header files, rebuild from the project root:

```bash
python tools/build_arm64.py --ndk "PATH_TO_INSTALLED_NDK"
```

For example, supply the full path to the installed NDK version directory, not the SDK root. Build the APK after the native build succeeds.

The optional `media_arm64.cmake` can be integrated into a CMake-based native build. Do not package duplicate versions of the same library through both prebuilt and externalNativeBuild paths.

Other supported ABIs use managed implementations for these helpers. Native loading failures can fall back, but a crash after entering native code is not handled by that loading fallback.

If enabling a custom R8/minification configuration, preserve the JNI names, including:

```proguard
-keep class com.linfranca.ytdlpmobile.NativeMedia { *; }
```

The reviewed release configuration has minification disabled.

### Native verification

```bash
python -m pip install unicorn pyelftools
python tools/verify_arm64.py
```

Prior verification of the supplied library passed 8,666 emulated native behavior checks. JVM adapter tests exercise managed fallbacks. These checks do not certify the release APK's packaging, signing, native loading, or recording behavior.

Native helpers target selected parsing, sorting, and serialization operations. Download speed remains dependent on the network/server, and media encoding remains backed by FFmpeg/platform codecs; no overall speed multiplier is claimed.

## Signed release APK

1. Select **Build → Generate Signed App Bundle or APK → APK**.
2. Use the existing application signing key for an update to an installed release.
3. Choose the release variant and complete the build.
4. Locate the generated APK and label the distribution copy `yt-dlp-Mobile-1.0.0.apk`.
5. Install and test that exact signed file.

Keep the signing keystore and credentials outside the public repository. Keep a private backup: losing the signing key prevents compatible updates to existing installations signed with it.

## Release validation

Check regular MP4/audio downloads, playlists/subtitles, cookies, Browser Assist, recording stop/save/discard, stalled recovery and timeout, audio timing, collections, PDF order, and linked/embedded video behavior. Test normal app reopening after these operations.

Verify the release APK includes `lib/arm64-v8a/libmedia_arm64.so` if distributing the native overlay. Build the APK from the same source revision that will be tagged for release.

## Repository contents and exclusions

Include source, resources, Gradle wrapper/configuration, native source, native rebuild tools, and the required bundled native library. Exclude generated build output, `.gradle/`, `local.properties`, signing secrets, cookies, account/API credentials, personal recordings, and private logs.

Do not blanket-ignore all `.so` files: the custom prebuilt native library is part of this project. Keep APK downloads in release assets rather than committing them to the source tree.

## Dependency/license review

Read [Third-party notices](../THIRD_PARTY_NOTICES.md) and resolve the pending [application license](../LICENSE) before publication. The wrapper declares GPL-3.0. An app source ZIP alone is not proof that all corresponding-source and notice requirements for bundled binaries are satisfied.
