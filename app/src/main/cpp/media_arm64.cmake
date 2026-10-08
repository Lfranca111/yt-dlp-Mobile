# Optional source build. The default patch uses jniLibs and requires no Gradle edits.
# If wiring this into externalNativeBuild, exclude/remove the matching prebuilt
# jniLibs .so to avoid packaging the same library twice.
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    enable_language(C ASM)
    add_library(media_arm64 SHARED media_jni.c media_scan_arm64.S media_sort_arm64.S
        media_binary_arm64.S media_policy_arm64.S media_pdf_arm64.S media_url_arm64.S)
    target_compile_options(media_arm64 PRIVATE -O2 -Wall -Wextra -Werror)
    target_link_options(media_arm64 PRIVATE -Wl,-z,max-page-size=16384,-z,relro,-z,now)
endif()
