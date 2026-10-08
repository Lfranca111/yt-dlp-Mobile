# Third-party notices

This document identifies the principal dependencies from the reviewed project configuration. It is a dependency notice inventory, not a complete license bundle or certification of distribution compliance. Transitive dependencies and the exact native binaries in a release also need review.

## Main components

| Component | Declared version / role | License information | Upstream |
| --- | --- | --- | --- |
| yt-dlp | Bundled/updateable download engine; runtime version can differ from app version | Source project uses the Unlicense; bundled distributions may include components with other licenses | [yt-dlp](https://github.com/yt-dlp/yt-dlp) |
| youtubedl-android library | `io.github.junkfood02.youtubedl-android:library:0.18.1` | Published component declares GPL-3.0 | [Project](https://github.com/yausername/youtubedl-android), [published metadata](https://central.sonatype.com/artifact/io.github.junkfood02.youtubedl-android/library/0.18.1) |
| youtubedl-android FFmpeg wrapper | `io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1` | Published wrapper component declares GPL-3.0; inspect bundled FFmpeg separately | [Published metadata](https://central.sonatype.com/artifact/io.github.junkfood02.youtubedl-android/ffmpeg/0.18.1) |
| FFmpeg | Media processing executable/libraries | Core commonly LGPL-2.1-or-later; enabled components/build options can change applicable terms | [License and legal information](https://ffmpeg.org/legal.html), [source](https://ffmpeg.org/download.html) |
| AndroidX | Core 1.15.0, AppCompat 1.7.0, Lifecycle 2.8.7, ViewPager2 1.1.0, WebKit 1.15.0 | Upstream AndroidX project uses Apache-2.0; retain component notices | [AndroidX source/license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |
| Material Components for Android | 1.12.0 | Apache-2.0 | [Project/license](https://github.com/material-components/material-components-android/blob/master/LICENSE) |
| Kotlin | Kotlin plugin/standard library | Apache-2.0; dependency notices may also apply | [Project/license](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| JUnit | 4.13.2, test dependency | Eclipse Public License 1.0 | [Project/license](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) |

Versions describe the configuration used while drafting these docs. The checked-in dependency graph and actual release APK determine what is distributed. Test/build-only tools are distinct from APK contents.

## Bundled native/runtime components

The APK can contain Python/runtime resources supplied by the Android download wrapper, FFmpeg, shared C++ runtime libraries, and their dependencies. Preserve upstream copyright/license notices and identify the exact versions, build configuration, and corresponding source for the binaries shipped.

The custom `libmedia_arm64.so` is supplied with its Kotlin adapter, C bridge, headers, and ARM64 assembly source in this repository. Its application license remains pending with the owner's decision. A prebuilt binary and source should be kept consistent by running the documented rebuild tool when native code changes.

## What still needs to be completed before distribution

- Select an application license compatible with the dependencies; the wrapper's GPL declaration cannot be replaced by labeling the entire bundle MIT or Unlicense.
- Include the applicable license texts and required copyright/NOTICE files for the actual distributed components.
- Provide corresponding source and build information wherever the applicable license requires it, including bundled native dependencies; linking only to a moving upstream branch may not identify the shipped binary's source.
- Record FFmpeg build options and libraries to determine the binary's applicable terms.
- Audit resolved transitive dependencies and downloaded runtime bundles, not only direct Gradle entries.

## Reference license texts

- [GNU GPL version 3](https://www.gnu.org/licenses/gpl-3.0.html)
- [GNU LGPL version 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
- [Apache License version 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [The Unlicense](https://unlicense.org/)

Third-party names identify the components used. This app is not an official release of yt-dlp, FFmpeg, or the supported websites.
