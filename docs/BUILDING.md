# Building yt-dlp Mobile

This guide covers the **1.0.74 release target**, following the 1.0.63 public baseline. Use the checked-in Gradle configuration as the authority for exact versions.

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

Keep the Gradle wrapper JAR and properties file. Merge the documentation ZIP at this root: `README.md` and `CHANGELOG.md` belong beside `app/`; `USER_GUIDE.md` and `BUILDING.md` belong under `docs/`. The relative links rely on that layout.

### Changed-files updates

Development source-update ZIPs contain only changed/new files. Apply them in order over the complete version stated in the patch README. Merge/replace matching files; do not delete the app folder or treat an incremental ZIP as a complete checkout. The 1.0.74 changed-files patch expects the complete 1.0.73 project. For an earlier install, use a complete source checkout of the intended version or apply the matching incremental updates in order. A public APK update does not require users to install each intermediate version.

Keep a backup or commit before applying a patch. Compare the final source against the intended base and build the merged project, not just the ZIP contents.

## Build environment

- Android Studio with the SDK components required by the project.
- JDK 17 for the checked Gradle/Android plugin configuration; configure the Gradle JDK accordingly.
- Android SDK Platform 35 for the documented `compileSdk`/`targetSdk`.
- Android NDK: the current app Gradle task uses its shared C++ runtime for FFmpeg packaging.
- Python 3 if rebuilding or verifying the custom ARM64 library.
- Internet access to resolve Gradle dependencies and download the wrapper distribution on the first build.

The app source reviewed here specifies minimum SDK 29, compile/target SDK 35, Java/Kotlin JVM target 17 and ABI filters `arm64-v8a`, `armeabi-v7a`, and `x86_64`. The earlier repository documentation recorded Android Gradle Plugin 8.7.3, Gradle 8.9 and Kotlin plugin 2.1.0; read the actual root plugin files and `gradle-wrapper.properties` for the project's current values. Do not overwrite a newer working configuration to match an older documentation snapshot or a temporary validation environment.

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

Unit tests do not substitute for WebView login, recording, SAF file access, theme/orientation changes, or PDF attachment tests on a device.

Some network fixtures use `jdk.httpserver`. The Gradle configuration makes that API available to unit-test compilation without adding it to the Android app. Use a full JDK 17 that includes its module files.

### What development validation establishes

The full debug build and canonical unit suite passed on 1.0.71 (651 passed, two skipped). Later patches had focused source checks; 1.0.74 passed 22 focused tests and a host VK replay decode. A full Android build, unit suite and lint run for the final release revision, and checks of the exact signed APK, remain separate. See the [website coverage tables](USER_GUIDE.md#website-support-and-dedicated-handlers) for test evidence and limitations.

## Version and launcher icon

In `app/build.gradle.kts`, the **1.0.74 release target** is:

```kotlin
versionCode = 133
versionName = "1.0.74"
```

Check this code against builds already distributed to the intended audience. Android updates also depend on the application ID and signing key.

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
4. Locate the generated APK and label the distribution copy consistently with the actual release, for example `yt-dlp-Mobile-VERSION.apk`. If the release instructions name `ytdlpMobile.apk`, upload that filename or update the instructions to match.
5. Install and test that exact signed file.

Keep the signing keystore and credentials outside the public repository. Keep a private backup: losing the signing key prevents compatible updates to existing installations signed with it.

## Release validation

Use the final release source and the exact signed APK intended for distribution.

- Test regular video/audio downloads, playlists, subtitle-only output, automatic captions, extra options/preset apply behavior, cookie import/removal, share links and normal fallback on a page without a dedicated handler.
- Test dedicated website options and explicit server/audio choices without silently substituting another selection. Check the warning/export case for inconclusive language metadata.
- Test Browser Assist single-source and Download all queueing, zoom buttons, web recovery for app-opening links, sign-in/verification, and expired media rediscovery.
- Test queue concurrency, priority, pause/resume, held preparation countdowns, fresh retry, retained fragments and partial export. Check recovery after an interrupted process without assuming missing live time can be recreated.
- Test recording start, stop/save, discard, stall recovery, unattended timeout, audio timing and the saved file. Start a second recording after the first finishes.
- Test upload baseline/new-item handling, live discovery/preparation, notify-only/automatic actions, independent rule schedules, allowed windows/time zones, network failures, foreground restrictions, restart restoration, and full log export.
- Test YouTube complete-audio selection, player-data HLS recovery, and startup fallback. Check VK upload monitoring with both link forms and verify live recording separately. Test Rumble with signed-in cookies and Vimeo playlist pairing in Browser Assist. A fixture or host check does not verify phone recording.
- Run `lintDebug`; verify the full recording through the last frame and isolate the resume-cache fixture failures before treating them as app regressions.
- Test saved-session reuse and visible agreement/login/verification prompts. Prompts must not be accepted automatically or misclassified as offline. Test uploads/live sections separately for custom monitoring sources.
- Test comic initial/future-page behavior, failed checks retaining the baseline, pending-page retry, and no automatic PDF rebuilding or destructive page reorder/overwrite.
- Test supported collections/listings, page order, folder permissions, linked/embedded PDF videos, and viewer limitations.
- Test Dark/Light/Follow device and all orientation modes while scrolling, typing, opening dialogs, and running a media task. Main navigation should remain pinned only on the three main pages. Check form/browser state, landscape scroll stability and the next task after completion.
- Test notifications, exact/background scheduling access, screen-off behavior and normal app reopening. Android/vendor battery settings and server access remain external constraints.

Verify the APK includes `lib/arm64-v8a/libmedia_arm64.so` when distributing the native overlay and the C++ runtimes required by the existing recorder packaging for supported ABIs. Build from the same source revision that will be tagged.

### Publish the verified build

Use a new version/tag for a new release rather than replacing source history under an unrelated existing tag. Attach the verified signed APK under release Assets, make the documented filename match, and retain the source/notices belonging to that exact revision. GitHub's automatically generated source archives are not APK installers. Publishing is a separate maintainer step; preparing these documents does not upload an APK or create a release.

## Repository contents and exclusions

Include source, resources, Gradle wrapper/configuration, native source, native rebuild tools, and the required bundled native library. Exclude generated build output, `.gradle/`, `local.properties`, signing secrets, cookies, account/API credentials, personal recordings, and private logs.

Do not blanket-ignore all `.so` files: the custom prebuilt native library is part of this project. Keep APK downloads in release assets rather than committing them to the source tree.

## Dependency/license review

Read [Third-party notices](../THIRD_PARTY_NOTICES.md) and resolve the pending [application license](../LICENSE) before publication. The wrapper declares GPL-3.0. An app source ZIP alone is not proof that all corresponding-source and notice requirements for bundled binaries are satisfied.
