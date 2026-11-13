plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseStoreFile = System.getenv("ETA_RELEASE_STORE_FILE")

/** 离线语音引擎 AAR：只有存在时才编译 src/localasr 并作为依赖打包；缺失时构建不受影响。 */
val localAsrAar = file("libs/sherpa-onnx.aar")
val releaseStorePassword = System.getenv("ETA_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("ETA_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ETA_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// CI 对非发版构建传入提交号，按 SemVer 构建元数据追加为 +<sha>，不影响版本先后；本地与发版构建不传
val buildMetadata = providers.gradleProperty("eta.buildMetadata").orNull?.takeIf { it.isNotBlank() }
require(buildMetadata == null || buildMetadata.matches(Regex("[0-9A-Za-z.-]+"))) {
    "eta.buildMetadata 只能包含字母、数字、点和连字符"
}
val buildMetadataSuffix = buildMetadata?.let { "+$it" }.orEmpty()

// ── 测试版（可与正式版并存安装）─────────────────────────────────────────────
// 同一 applicationId 在系统里只能存在一份，共存只能换安装身份；namespace 保持不变，
// 所以源码包名、类名、Xposed 入口（META-INF/xposed/java_init.list）与 R 类都不受影响。
// 用法：./gradlew :app:assembleRelease -Peta.beta=true
// 版本号：测试线独立计号，今天首发 2026102501 / 3.4.0-beta.1。
val isBetaBuild = providers.gradleProperty("eta.beta").orNull.toBoolean()
val betaApplicationId = "io.github.mangi.eta.beta"

android {
    namespace = "io.github.mangi.eta"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = if (isBetaBuild) betaApplicationId else "io.github.mangi.eta"
        minSdk = 33
        targetSdk = 37
        // versionCode 规则：yyyyMMdd + 两位当日序号（01 起），发版时随 versionName 一起手动递增。
        // 跨零点必须换成当天日期、序号归 01，不能接着前一天的号往下加——
        // 之前从 2026102020 起就一直在沿用 10-20 的日期，日期不再反映实际构建日。
        // 两条线各自计号：正式线沿用主干号段，测试线独立递增（beta.8）。
        versionCode = if (isBetaBuild) 2026110806 else 2026110805
        versionName = if (isBetaBuild) "3.4.0-beta.57" else "3.3.4"
        // 桌面名与包名一起区分，避免志愿者装错包；正式版继续用 @string/app_name。
        manifestPlaceholders["applicationLabel"] =
            if (isBetaBuild) "Eta β" else "@string/app_name"
        // 只对测试包生效的运行时标记：常驻测试版提示条、同意页强制展示等。
        buildConfigField("boolean", "BETA_BUILD", isBetaBuild.toString())
        testInstrumentationRunner = "io.github.mangi.eta.validation.DeviceValidationRunner"

        ndk {
            // 同时过滤本地与依赖 AAR 的原生库，确保 APK 仅包含 ARM64。
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // 三种方案一起签：v1 给老工具/非 Android 校验器认，v2 是 Android 7+ 的主校验，
                // v3 是 Android 9+ 的换钥与降级保护。只签 v3 时，部分 root 模块、MT 管理器、
                // 第三方安装器会报「未签名」或校验失败。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 版本名带构建类型标记，设置页与 APK 文件名据此区分 Debug 包
            versionNameSuffix = "-debug" + buildMetadataSuffix
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            versionNameSuffix = buildMetadataSuffix
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    /**
     * 离线语音：只有 app/libs/sherpa-onnx.aar 存在时，才把 src/localasr 加入编译。
     * 没有 AAR 的构建完全不受影响，AsrProvider.LOCAL 会降级为「组件未编入」。
     */
    sourceSets {
        getByName("main") {
            if (localAsrAar.exists()) {
                // Kotlin 源码必须走 kotlin.srcDir；写进 java.srcDir 不会被 Kotlin 编译器读取，
                // 结果是类没进 dex、运行时反射失败并显示「未包含离线识别组件」。
                kotlin.srcDir("src/localasr/kotlin")
            }
        }
    }

    androidResources {
        localeFilters += listOf("b+zh+Hans")
    }

    packaging {
        // 不压缩 dex：上游 3.3.4 开启 useLegacyPackaging 后，APK 小约 4 MB，
        // 但安装时必须解压并生成完整 vdex（实测多占 8.5 MB），本机长期使用得不偿失。
        // 只在需要对外分发小包时临时打开。
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // versionCode 采用日期编码，已发布值只能递增；上限 2100000000 足够覆盖到 2099 年。
        disable += "HighAppVersionCode"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 在高版本 JDK 下需要访问内部 API，参数只作用于测试 JVM。
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        // 文件名只取 versionName：构建类型标记与 CI 构建元数据都由 versionNameSuffix 带出
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { "Eta-v$it.apk" })
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
    // 离线语音引擎：可选依赖，AAR 缺失时 src/localasr 不参与编译
    if (localAsrAar.exists()) implementation(files(localAsrAar))
    implementation(libs.jsoup)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    // 只使用 GFM 解析器；聊天渲染层由 ui/markdown 自建，按块冻结并接入逐字显现。
    implementation(libs.intellij.markdown)
    implementation(libs.hidden.api.bypass)

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}

// Compose 编译器会额外生成「Compose 映射报告」任务：只产出报告，不影响 APK 内容，
// 但在本机 PRoot 环境里偶发静默卡死（进程无声消失、日志停在那一行）。直接关掉。
tasks.configureEach {
    if (name.endsWith("ComposeMappingErrors") || name.endsWith("ComposeMapping")) {
        enabled = false
    }
}
