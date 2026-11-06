plugins {
    alias(libs.plugins.android.application)
}

// ─────────────────────────────────────────────────────────────────────────────
// probe-app —— 进程内 Node 探针载体（specs/101-probe-app）
//
// · 依赖姿势照 samples/minimal-app：bridge 用 implementation(project)，引擎包用 runtimeOnly
//   （编译期不依赖引擎符号，引擎可独立换版）。
// · 引擎版本钉 26.13.0（spec.md §8 依赖表 / FR-101-PROBE-006 版本契约）；
//   注意根 gradle/libs.versions.toml 的 [versions]nodeRuntime=24.2.0 是 minimal-app 的既有配对，
//   本模块显式钉 26.13.0（本机 gradle 缓存已命中 26.13.0 AAR，离线可编）。
// · ABI 仅 arm64-v8a（本仓只发的架构，spec.md §7 NFR）。
// ─────────────────────────────────────────────────────────────────────────────

android {
    namespace = "org.fellow99.nodebridge.probe"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // applicationId 与用例正典（test-cases.md §4 设备执行）一致：
        //   adb shell am start -n org.fellow99.nodebridge.probe/.MainActivity
        //   adb shell run-as org.fellow99.nodebridge.probe cat files/probe-report.json
        applicationId = "org.fellow99.nodebridge.probe"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdkModern.get().toInt()
        versionCode = 1
        // 探针 app 不发布；版本号 = 配对 Node 版本（spec.md §2 不在范围 "探针 App 的发布"）
        versionName = "26.13.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // 与生产消费方 pi-desktop-android 同形态：.so 物理落盘 nativeLibraryDir（legacy=true）。
            // 20261106 UAT 排障曾怀疑打包形态是 PRB-04a/04b（addon napi 符号解析失败）根因并做 A/B，
            // 证伪：legacy=false/true 下均复现。真实根因/修复 = addon 显式链接 libnode.so 留下
            // DT_NEEDED（见 tools/build-bridge.mjs），与打包形态无关。此处保留 legacy=true 仅因
            // 对齐生产打包方式，属可选项。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":node-bridge"))
    // 引擎包：只装 libnode.so（+ libc++_shared.so），从 Maven Central 消费。
    // 用 runtimeOnly：bridge 编译期不依赖任何引擎符号（-z undefs），引擎可独立换版。
    runtimeOnly("io.github.fellow99:node-runtime:26.13.0")
}
