import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.artifacts.dsl.LockMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.net.URI
import java.util.Locale
import java.util.zip.ZipFile

buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Only real build/test configurations are resolved. AGP's detached Lint/AAPT2
// artifacts are covered separately by global dependency verification.
val dependencyConfigurationFile = rootProject.file("gradle/hans-dependency-configurations.json")
val lockedDependencyConfigurations =
    (JsonSlurper().parse(dependencyConfigurationFile) as List<*>).map {
        check(it is String && it.isNotBlank()) { "Invalid locked dependency configuration" }
        it
    }
check(lockedDependencyConfigurations.distinct().size == lockedDependencyConfigurations.size) {
    "Duplicate locked dependency configuration"
}
dependencyLocking {
    lockMode.set(LockMode.STRICT)
}
configurations.configureEach {
    if (name in lockedDependencyConfigurations) {
        resolutionStrategy.activateDependencyLocking()
    }
}
tasks.register("verifyPinnedDependencyGraph") {
    group = "verification"
    description = "Resolves the explicit Standard build/test graph against version locks and checksums."
    inputs.file(dependencyConfigurationFile)
    doLast {
        lockedDependencyConfigurations.sorted().forEach { configurationName ->
            val configuration = configurations.getByName(configurationName)
            check(configuration.isCanBeResolved) {
                "Locked dependency configuration cannot be resolved: " + configurationName
            }
            // AndroidTest depends on this app through several outgoing artifact
            // types. Verify external modules without selecting/building an
            // arbitrary project artifact; the normal APK tasks verify those.
            configuration.incoming.artifactView {
                componentFilter {
                    it is org.gradle.api.artifacts.component.ModuleComponentIdentifier
                }
            }.files.files
            logger.lifecycle("Verified dependency configuration: {}", configurationName)
        }
    }
}

val hansNdkVersion = "29.0.14206865"
@Suppress("UNCHECKED_CAST")
val hansReleaseDescriptor = JsonSlurper().parse(rootProject.file("release/release.json")) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val hansStandardRelease =
    (hansReleaseDescriptor.getValue("applications") as Map<String, Any>).getValue("standard") as Map<String, Any>
val hansStandardReleaseVersionCode = (hansStandardRelease.getValue("versionCode") as Number).toInt()
val hansStandardReleaseVersionName = hansStandardRelease.getValue("versionName") as String
val hansUpdateUrl = if (hansStandardRelease.containsKey("updateUrl")) {
    val value = hansStandardRelease["updateUrl"]
    check(value is String) {
        "applications.standard.updateUrl must be a string when present"
    }
    value
} else {
    ""
}
if (hansUpdateUrl.isNotEmpty()) {
    val uri = runCatching { URI(hansUpdateUrl) }.getOrNull()
    check(
        hansUpdateUrl == hansUpdateUrl.trim() && hansUpdateUrl.length <= 2_048 &&
            hansUpdateUrl.none {
                it.code !in 0x21..0x7e || it.isISOControl() || it.isWhitespace() || it == '\\'
            } &&
            hansUpdateUrl.startsWith("https://") && uri != null && uri.scheme == "https" &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            uri.port in setOf(-1, 443)
    ) {
        "applications.standard.updateUrl must be a fixed HTTPS URL without credentials or a fragment"
    }
}
val hansReleaseProfile = hansReleaseDescriptor.getValue("releaseProfile") as String
check(hansReleaseProfile == "standard-v2") {
    "Unsupported Hans releaseProfile: $hansReleaseProfile"
}
val runtimeLockFile = rootProject.file("runtime/runtime.lock.json")
@Suppress("UNCHECKED_CAST")
val runtimeLock = JsonSlurper().parse(runtimeLockFile) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val codexRuntimeLock = runtimeLock.getValue("runtime") as Map<String, Any>
val codexRuntimeVersion = codexRuntimeLock.getValue("version") as String
val codexRuntimeSha256 = codexRuntimeLock.getValue("extractedSha256") as String
val codexRuntimeBytes = (codexRuntimeLock.getValue("extractedBytes") as Number).toLong()
val codexRuntimeApkName = codexRuntimeLock.getValue("apkLibraryName") as String
@Suppress("UNCHECKED_CAST")
val codeModeHostLock = runtimeLock.getValue("codeModeHost") as Map<String, Any>
val codeModeHostVersion = codeModeHostLock.getValue("version") as String
val codeModeHostSha256 = codeModeHostLock.getValue("extractedSha256") as String
val codeModeHostBytes = (codeModeHostLock.getValue("extractedBytes") as Number).toLong()
val codeModeHostApkName = codeModeHostLock.getValue("apkLibraryName") as String
val pythonRuntimeLockFile = rootProject.file("python-runtime/python.lock.json")
val pythonNativePackagesLockFile = rootProject.file("python-runtime/native-packages.lock.json")
@Suppress("UNCHECKED_CAST")
val pythonRuntimeLock = JsonSlurper().parse(pythonRuntimeLockFile) as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val pythonRuntime = pythonRuntimeLock.getValue("runtime") as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val pythonRuntimeAndroid = pythonRuntime.getValue("android") as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val pythonRuntimeStdlib = pythonRuntime.getValue("stdlib") as Map<String, Any>
val pythonRuntimeVersion = pythonRuntime.getValue("version") as String
val pythonRuntimeAbi = pythonRuntimeAndroid.getValue("abi") as String
val pythonRuntimeHostApi = (pythonRuntimeAndroid.getValue("hostApplicationMinimumApi") as Number).toInt()
val pythonRuntimeStdlibSha256 = pythonRuntimeStdlib.getValue("expectedSha256") as String
val pythonRuntimeStdlibBytes = (pythonRuntimeStdlib.getValue("expectedBytes") as Number).toLong()
check(pythonRuntimeAbi == "arm64-v8a" && pythonRuntimeHostApi == 31) {
    "Hans CPython must remain an arm64-v8a Android-31 host runtime"
}
val webRtcCoordinate = "io.github.webrtc-sdk:android-prefixed-stripped:144.7559.12"
val webRtcAarSha256 = "d945209f4f38615ee07a8d8d94b14b08cf2a0d75cd71b46d5e1574119e338ec0"
val bundledHansSetupRoot = rootProject.file("bundled-plugins/hans-setup")
@Suppress("UNCHECKED_CAST")
val bundledHansSetupManifest =
    JsonSlurper().parse(bundledHansSetupRoot.resolve(".codex-plugin/plugin.json")) as Map<String, Any>
val bundledHansSetupVersion = bundledHansSetupManifest.getValue("version") as String
val bundledHansSetupAssets = layout.buildDirectory.dir("generated/bundledHansSetup/assets")
val hansReleaseConfigAssets = layout.buildDirectory.dir("generated/hansReleaseConfig/assets")

val pinnedWebRtcAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

android {
    namespace = "ai.hans.standard"
    compileSdk = 36
    ndkVersion = hansNdkVersion

    defaultConfig {
        applicationId = "ai.hans.standard"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"

        // Do not let library-only translations claim languages Hans does not support.
        // Unqualified app resources remain the complete English fallback.
        resourceConfigurations += listOf("en", "de")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "CODEX_RUNTIME_VERSION", "\"$codexRuntimeVersion\"")
        buildConfigField("String", "CODEX_RUNTIME_SHA256", "\"$codexRuntimeSha256\"")
        buildConfigField("long", "CODEX_RUNTIME_BYTES", "${codexRuntimeBytes}L")
        buildConfigField(
            "String",
            "HANS_UPDATE_URL",
            "\"${hansUpdateUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
        buildConfigField("String", "CODE_MODE_HOST_VERSION", "\"$codeModeHostVersion\"")
        buildConfigField("String", "CODE_MODE_HOST_SHA256", "\"$codeModeHostSha256\"")
        buildConfigField("long", "CODE_MODE_HOST_BYTES", "${codeModeHostBytes}L")
        buildConfigField("String", "PYTHON_RUNTIME_VERSION", "\"$pythonRuntimeVersion\"")
        buildConfigField("String", "PYTHON_RUNTIME_ABI", "\"$pythonRuntimeAbi\"")
        buildConfigField("String", "PYTHON_STDLIB_SHA256", "\"$pythonRuntimeStdlibSha256\"")
        buildConfigField("long", "PYTHON_STDLIB_BYTES", "${pythonRuntimeStdlibBytes}L")
        buildConfigField(
            "String",
            "BUNDLED_SETUP_PLUGIN_VERSION",
            "\"$bundledHansSetupVersion\"",
        )
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        assets.srcDir(bundledHansSetupAssets)
        assets.srcDir(hansReleaseConfigAssets)
        assets.srcDir(layout.buildDirectory.dir("generated/pythonRuntime/staged/assets"))
        assets.srcDir(layout.buildDirectory.dir("generated/pythonResolver/assets"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/hansProbe/jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/hansFileUnlink/jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/codexRuntime/jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/codexTranscription/jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/pythonRuntime/staged/jniLibs"))
        jniLibs.srcDir(layout.buildDirectory.dir("generated/pythonBridge/jniLibs"))
    }

    packaging {
        jniLibs {
            // The probe and, later, Codex are packaged as signed APK-native
            // artifacts and extracted into nativeLibraryDir. They are never
            // written into an app-writable executable directory.
            useLegacyPackaging = true
            // The pinned upstream artifact is already stripped. Asking AGP to
            // preserve it is also what guarantees byte-for-byte identity with
            // runtime.lock.json inside the final APK.
            keepDebugSymbols += "**/$codexRuntimeApkName"
            keepDebugSymbols += "**/$codeModeHostApkName"
            keepDebugSymbols += "**/libcodex_transcribe.so"
            keepDebugSymbols += "**/libpython3.14.so"
            keepDebugSymbols += "**/lib*_python.so"
            keepDebugSymbols += "**/libhans_py_*.so"
            keepDebugSymbols += "**/libhans_python_jni.so"
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

// JVM presentation fixtures read the actual localized XMLs without a Robolectric runtime.
// Text-only changes must invalidate those tests even when generated R IDs stay unchanged.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/res") { include("**/*.xml") })
        .withPropertyName("hansLocalizationResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val generateHansReleaseConfig by tasks.registering {
    group = "build"
    description = "Generates the APK-internal release configuration bound to release.json."
    inputs.property("schemaVersion", 1)
    inputs.property("updateUrl", hansUpdateUrl)
    val output = hansReleaseConfigAssets.map { it.file("hans/release-config.json") }
    outputs.file(output)

    doLast {
        val target = output.get().asFile
        target.parentFile.mkdirs()
        val document = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "updateUrl" to hansUpdateUrl,
        )
        target.writeText(JsonOutput.toJson(document) + "\n", Charsets.UTF_8)
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(hansStandardReleaseVersionCode)
            output.versionName.set(hansStandardReleaseVersionName)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val probeSource = layout.projectDirectory.file("src/main/cpp/hans_native_probe.c")
val probeOutput = layout.buildDirectory.file(
    "generated/hansProbe/jniLibs/arm64-v8a/libhans_probe.so",
)

val pythonRuntimeRoot = rootProject.file("python-runtime")
val stagedPythonRuntime = layout.buildDirectory.dir("generated/pythonRuntime/staged")
val stagedPythonPrefix = stagedPythonRuntime.map { it.dir("prefix") }
val pythonResolverRoot = pythonRuntimeRoot.resolve("resolver")
val stagedPythonResolverAssets = layout.buildDirectory.dir("generated/pythonResolver/assets")
val stagedPythonResolverBundle = stagedPythonResolverAssets.map {
    it.file("hans/python/resolver.pyz")
}
val pythonNativeSource = layout.projectDirectory.dir("src/main/cpp/python")
val pythonNativeBuildDirectory = layout.buildDirectory.dir("intermediates/hansPythonCmake")
val pythonNativeOutputDirectory = layout.buildDirectory.dir(
    "generated/pythonBridge/jniLibs/arm64-v8a",
)
val pythonNativeOutput = pythonNativeOutputDirectory.map { it.file("libhans_python_jni.so") }
val hansHostPython = providers.environmentVariable("HANS_PYTHON").orElse("python3")
val hansHostCmake = providers.environmentVariable("HANS_CMAKE").orElse("cmake")

val stagePythonResolverBundle by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pinned, deterministic, source-only Python dependency resolver."
    inputs.file(pythonResolverRoot.resolve("resolver.lock.json"))
    inputs.file(pythonResolverRoot.resolve("hans_resolver_worker.py"))
    inputs.dir(pythonResolverRoot.resolve("vendor"))
    inputs.file(pythonResolverRoot.resolve("scripts/build_resolver_bundle.py"))
    outputs.file(stagedPythonResolverBundle)
    doFirst {
        commandLine(
            hansHostPython.get(),
            pythonResolverRoot.resolve("scripts/build_resolver_bundle.py").absolutePath,
            "--output",
            stagedPythonResolverBundle.get().asFile.absolutePath,
        )
    }
}

val testPythonResolverHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the pinned resolver's PEP 440/508 and backtracking host tests."
    inputs.dir(pythonResolverRoot.resolve("tests"))
    inputs.dir(pythonResolverRoot.resolve("vendor"))
    inputs.file(pythonResolverRoot.resolve("hans_resolver_worker.py"))
    inputs.file(pythonResolverRoot.resolve("scripts/build_resolver_bundle.py"))
    environment("PYTHONDONTWRITEBYTECODE", "1")
    doFirst {
        commandLine(
            hansHostPython.get(),
            "-m",
            "unittest",
            "discover",
            "-s",
            pythonResolverRoot.resolve("tests").absolutePath,
            "-p",
            "test_*.py",
            "-v",
        )
    }
}

val stagePythonRuntime by tasks.registering(Exec::class) {
    group = "build"
    description = "Stages the digest-pinned upstream CPython Android runtime reproducibly."
    inputs.file(pythonRuntimeLockFile)
    inputs.file(pythonNativePackagesLockFile)
    inputs.dir(pythonRuntimeRoot.resolve("runtime"))
    inputs.file(pythonRuntimeRoot.resolve("scripts/stage_runtime.py"))
    outputs.dir(stagedPythonRuntime)
    environment("HANS_PYTHON_RUNTIME_OFFLINE", if (gradle.startParameter.isOffline) "1" else "0")
    doFirst {
        commandLine(
            hansHostPython.get(),
            pythonRuntimeRoot.resolve("scripts/stage_runtime.py").absolutePath,
            "--lock",
            pythonRuntimeLockFile.absolutePath,
            "--runtime-source",
            pythonRuntimeRoot.resolve("runtime").absolutePath,
            "--cache",
            pythonRuntimeRoot.resolve("build/downloads").absolutePath,
            "--output",
            stagedPythonRuntime.get().asFile.absolutePath,
        )
    }
}

val verifyStagedPythonRuntime by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies CPython digests, deterministic assets, curated modules, and 16 KiB ELF alignment."
    dependsOn(stagePythonRuntime)
    inputs.file(pythonRuntimeLockFile)
    inputs.file(pythonNativePackagesLockFile)
    inputs.file(pythonRuntimeRoot.resolve("scripts/verify_runtime.py"))
    inputs.dir(stagedPythonRuntime)
    doFirst {
        commandLine(
            hansHostPython.get(),
            pythonRuntimeRoot.resolve("scripts/verify_runtime.py").absolutePath,
            "--lock",
            pythonRuntimeLockFile.absolutePath,
            "--stage-root",
            stagedPythonRuntime.get().asFile.absolutePath,
        )
    }
}

val testPythonRuntimeHost by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs CPython dispatcher, JNI-contract, JSON, and SHA-256 host tests."
    inputs.dir(pythonRuntimeRoot.resolve("tests"))
    inputs.dir(pythonRuntimeRoot.resolve("runtime"))
    inputs.dir(layout.projectDirectory.dir("src/main/cpp/python"))
    doFirst {
        commandLine(
            hansHostPython.get(),
            "-m",
            "unittest",
            "discover",
            "-s",
            pythonRuntimeRoot.resolve("tests").absolutePath,
            "-p",
            "test_*.py",
            "-v",
        )
    }
}

val configureHansPythonNativeBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Configures the API-31 ARM64 JNI host for the pinned CPython runtime."
    dependsOn(verifyStagedPythonRuntime)
    inputs.dir(pythonNativeSource)
    inputs.dir(stagedPythonPrefix)
    inputs.property("hansNdkVersion", hansNdkVersion)
    inputs.property("pythonRuntimeHostApi", pythonRuntimeHostApi)
    outputs.file(pythonNativeBuildDirectory.map { it.file("CMakeCache.txt") })
    doFirst {
        val buildDirectory = pythonNativeBuildDirectory.get().asFile.apply { mkdirs() }
        val outputDirectory = pythonNativeOutputDirectory.get().asFile.apply { mkdirs() }
        val toolchain = android.ndkDirectory.resolve("build/cmake/android.toolchain.cmake")
        check(toolchain.isFile) { "Android NDK CMake toolchain is missing: $toolchain" }
        commandLine(
            hansHostCmake.get(),
            "-S",
            pythonNativeSource.asFile.absolutePath,
            "-B",
            buildDirectory.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
            "-DANDROID_ABI=$pythonRuntimeAbi",
            "-DANDROID_PLATFORM=android-$pythonRuntimeHostApi",
            "-DANDROID_STL=c++_static",
            "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
            "-DHANS_PYTHON_PREFIX=${stagedPythonPrefix.get().asFile.absolutePath}",
            "-DHANS_PYTHON_OUTPUT_DIRECTORY=${outputDirectory.absolutePath}",
        )
    }
}

val compileHansPythonNativeBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the hardened, 16-KiB-compatible CPython JNI host."
    dependsOn(configureHansPythonNativeBridge)
    inputs.dir(pythonNativeSource)
    inputs.dir(stagedPythonPrefix)
    inputs.file(pythonNativeBuildDirectory.map { it.file("CMakeCache.txt") })
    outputs.file(pythonNativeOutput)
    doFirst {
        commandLine(
            hansHostCmake.get(),
            "--build",
            pythonNativeBuildDirectory.get().asFile.absolutePath,
            "--parallel",
        )
    }
}

val compileHansNativeProbe by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the signed-APK ARM64 native executable probe."
    inputs.file(probeSource)
    outputs.file(probeOutput)

    doFirst {
        val hostTag = when {
            System.getProperty("os.name").lowercase(Locale.US).contains("linux") -> "linux-x86_64"
            System.getProperty("os.name").lowercase(Locale.US).contains("mac") -> "darwin-x86_64"
            System.getProperty("os.name").lowercase(Locale.US).contains("windows") -> "windows-x86_64"
            else -> error("Unsupported NDK build host: ${System.getProperty("os.name")}")
        }
        val compilerName = if (hostTag.startsWith("windows")) {
            "aarch64-linux-android31-clang.cmd"
        } else {
            "aarch64-linux-android31-clang"
        }
        val compiler = android.ndkDirectory.resolve(
            "toolchains/llvm/prebuilt/$hostTag/bin/$compilerName",
        )
        check(compiler.isFile) {
            "Android NDK $hansNdkVersion is missing the ARM64 compiler at $compiler"
        }
        probeOutput.get().asFile.parentFile.mkdirs()
        commandLine(
            compiler.absolutePath,
            probeSource.asFile.absolutePath,
            "-std=c17",
            "-O2",
            "-fPIE",
            "-pie",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-Wl,--build-id=sha1",
            "-Wl,-z,relro",
            "-Wl,-z,now",
            "-o",
            probeOutput.get().asFile.absolutePath,
        )
    }
}

val compileHansFileUnlinkBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the root-free public-NDK file-only unlink JNI bridge."
    val source = layout.projectDirectory.file("src/main/cpp/hans_file_unlink_jni.c")
    val output = layout.buildDirectory.file("generated/hansFileUnlink/jniLibs/arm64-v8a/libhans_file_unlink_jni.so")
    inputs.file(source)
    outputs.file(output)
    doFirst {
        val hostTag = when {
            System.getProperty("os.name").lowercase(Locale.US).contains("linux") -> "linux-x86_64"
            System.getProperty("os.name").lowercase(Locale.US).contains("mac") -> "darwin-x86_64"
            System.getProperty("os.name").lowercase(Locale.US).contains("windows") -> "windows-x86_64"
            else -> error("Unsupported NDK build host: ${System.getProperty("os.name")}")
        }
        val compilerName = if (hostTag.startsWith("windows")) "aarch64-linux-android31-clang.cmd" else "aarch64-linux-android31-clang"
        val compiler = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$compilerName")
        check(compiler.isFile) { "Android NDK $hansNdkVersion ARM64 compiler missing" }
        output.get().asFile.parentFile.mkdirs()
        commandLine(compiler.absolutePath, source.asFile.absolutePath,
            "-std=c17", "-O2", "-fPIC", "-shared", "-Wall", "-Wextra", "-Werror",
            "-Wl,--build-id=sha1", "-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,max-page-size=16384",
            "-o", output.get().asFile.absolutePath)
    }
}

val codexRuntimeJniLibs = layout.buildDirectory.dir("generated/codexRuntime/jniLibs")
val codexRuntimeOutput = codexRuntimeJniLibs.map {
    it.file("arm64-v8a/$codexRuntimeApkName")
}
val codeModeHostOutput = codexRuntimeJniLibs.map {
    it.file("arm64-v8a/$codeModeHostApkName")
}

val stageBundledHansSetup by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the validated Hans Setup plugin as deterministic non-executable assets."
    inputs.property("standardPromptSanitizerVersion", 2)

    from(bundledHansSetupRoot.resolve(".codex-plugin/plugin.json")) {
        into("hans/bundled-plugins/hans-setup")
        rename { "manifest.json" }
    }
    from(bundledHansSetupRoot.resolve("skills/setup-hans-device/SKILL.md")) {
        into("hans/bundled-plugins/hans-setup/skills/setup-hans-device")
        filter { line: String ->
            if (line.contains("Rootfunktionen gehören ausschließlich")) {
                "Bei einer Standardinstallation beschreibe nur öffentliche Android-APIs, erteilte Berechtigungen, Bedienungshilfe und tatsächlich geprüfte Fähigkeiten."
            } else {
                line.replace(
                    "Behaupte niemals Rootzugriff oder eine nicht bestätigte Fähigkeit.",
                    "Behaupte niemals einen nicht bestätigten Zugriff oder eine nicht bestätigte Fähigkeit.",
                )
            }
        }
    }
    into(bundledHansSetupAssets)
    includeEmptyDirs = false
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val packageCodexRuntime by tasks.registering(Exec::class) {
    group = "build"
    description = "Downloads, verifies, and packages the pinned official Codex App Server and Code Mode host."
    inputs.file(runtimeLockFile)
    inputs.file(rootProject.file("runtime/transcription/helper.lock.json"))
    inputs.files(
        rootProject.file("runtime/scripts/package-official-musl.sh"),
        rootProject.file("runtime/scripts/verify-package.sh"),
    )
    outputs.file(codexRuntimeOutput)
    outputs.file(codeModeHostOutput)
    environment("HANS_RUNTIME_OFFLINE", if (gradle.startParameter.isOffline) "1" else "0")

    commandLine(
        rootProject.file("runtime/scripts/package-official-musl.sh").absolutePath,
        codexRuntimeJniLibs.get().dir("arm64-v8a").asFile.absolutePath,
    )
}

val packageCodexTranscription by tasks.registering(Exec::class) {
    group = "build"
    description = "Verifies and stages the source-built, subscription-only transcription helper."
    val helperRoot = rootProject.file("runtime/transcription")
    inputs.files(helperRoot.resolve("source.lock.json"), helperRoot.resolve("helper.lock.json"),
        helperRoot.resolve("Cargo.toml"), helperRoot.resolve("Cargo.lock"))
    inputs.dir(helperRoot.resolve("src"))
    inputs.file(helperRoot.resolve("build/artifacts/0.1.0/libcodex_transcribe.so"))
    inputs.files(rootProject.file("runtime/scripts/package-transcription-helper.sh"),
        rootProject.file("runtime/tests/verify-transcription-elf.py"))
    val output = layout.buildDirectory.dir("generated/codexTranscription/jniLibs/arm64-v8a")
    outputs.file(output.map { it.file("libcodex_transcribe.so") })
    commandLine("/bin/sh", rootProject.file("runtime/scripts/package-transcription-helper.sh").absolutePath,
        "--stage", output.get().asFile.absolutePath)
}

tasks.configureEach {
    if (
        name.startsWith("merge") && name.endsWith("Assets") ||
        name.contains("Lint", ignoreCase = true)
    ) {
        dependsOn(generateHansReleaseConfig)
        dependsOn(stageBundledHansSetup)
        dependsOn(verifyStagedPythonRuntime)
        dependsOn(stagePythonResolverBundle)
    }
    if (
        name.startsWith("merge") &&
        (name.endsWith("NativeLibs") || name.endsWith("JniLibFolders"))
    ) {
        dependsOn(compileHansNativeProbe)
        dependsOn(compileHansFileUnlinkBridge)
        dependsOn(packageCodexRuntime)
        dependsOn(packageCodexTranscription)
        dependsOn(verifyStagedPythonRuntime)
        dependsOn(compileHansPythonNativeBridge)
    }
}

fun registerCodexRuntimeApkVerification(
    taskName: String,
    flavor: String,
) = tasks.register(taskName) {
    group = "verification"
    description = "Verifies the exact pinned Codex executable bytes in the $flavor debug APK."
    dependsOn("assemble${flavor.replaceFirstChar(Char::uppercase)}Debug")

    val debugApk = layout.buildDirectory.file(
        "outputs/apk/$flavor/debug/app-$flavor-debug.apk",
    )
    inputs.file(debugApk)
    inputs.file(runtimeLockFile)
    inputs.file(rootProject.file("runtime/transcription/helper.lock.json"))

    doLast {
        val apk = debugApk.get().asFile
        check(apk.isFile) { "$flavor debug APK does not exist: $apk" }
        ZipFile(apk).use { zip ->
            fun verifyExecutable(entryName: String, expectedBytes: Long, expectedSha: String) {
                val entryPath = "lib/arm64-v8a/$entryName"
                val entry = checkNotNull(zip.getEntry(entryPath)) {
                    "$flavor debug APK is missing $entryPath"
                }
                check(entry.size == expectedBytes) {
                    "Packaged $entryName size mismatch: expected $expectedBytes, got ${entry.size}"
                }
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                check(actualSha == expectedSha) {
                    "Packaged $entryName SHA-256 mismatch: expected $expectedSha, got $actualSha"
                }
                logger.lifecycle(
                    "Verified {} APK executable {}: {} bytes sha256={}",
                    flavor,
                    entryName,
                    entry.size,
                    actualSha,
                )
            }

            verifyExecutable(codexRuntimeApkName, codexRuntimeBytes, codexRuntimeSha256)
            verifyExecutable(codeModeHostApkName, codeModeHostBytes, codeModeHostSha256)
            val helper = JsonSlurper().parse(rootProject.file("runtime/transcription/helper.lock.json")) as Map<*, *>
            verifyExecutable("libcodex_transcribe.so", (helper["bytes"] as Number).toLong(),
                helper["sha256"] as String)
        }
    }
}

val verifyStandardDebugApkCodexRuntime = registerCodexRuntimeApkVerification(
    taskName = "verifyStandardDebugApkCodexRuntime",
    flavor = "standard",
)
val verifyDebugApkCodexRuntime by tasks.registering {
    group = "verification"
    description = "Compatibility alias for the permanent Standard APK verification."
    dependsOn(verifyStandardDebugApkCodexRuntime)
}

fun ZipFile.dexPayloads(): List<String> = entries().asSequence()
    .filter { entry -> entry.name.matches(Regex("classes(?:[0-9]+)?\\.dex")) }
    .map { entry ->
        getInputStream(entry).use { input ->
            input.readBytes().toString(Charsets.ISO_8859_1)
        }
    }
    .toList()

fun ZipFile.assetText(path: String): String {
    val entry = checkNotNull(getEntry(path)) { "APK is missing required asset $path" }
    return getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

val standardDebugApk = layout.buildDirectory.file(
    "outputs/apk/standard/debug/app-standard-debug.apk",
)
val standardReleaseApk = layout.buildDirectory.file(
    "outputs/apk/standard/release/app-standard-release-unsigned.apk",
)
val standardDebugMergedManifest = layout.buildDirectory.file(
    "intermediates/merged_manifest/standardDebug/processStandardDebugMainManifest/AndroidManifest.xml",
)
val standardReleaseMergedManifest = layout.buildDirectory.file(
    "intermediates/merged_manifest/standardRelease/processStandardReleaseMainManifest/AndroidManifest.xml",
)

val verifyStandardDebugApkPythonRuntime by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the exact isolated CPython payload in the Standard debug APK."
    dependsOn(
        "assembleStandardDebug",
        verifyStagedPythonRuntime,
        stagePythonResolverBundle,
        compileHansPythonNativeBridge,
    )
    inputs.file(pythonRuntimeLockFile)
    inputs.file(pythonNativePackagesLockFile)
    inputs.file(pythonRuntimeRoot.resolve("scripts/verify_runtime.py"))
    inputs.dir(stagedPythonRuntime)
    inputs.file(pythonNativeOutput)
    inputs.file(stagedPythonResolverBundle)
    inputs.file(standardDebugApk)
    doFirst {
        commandLine(
            hansHostPython.get(),
            pythonRuntimeRoot.resolve("scripts/verify_runtime.py").absolutePath,
            "--lock",
            pythonRuntimeLockFile.absolutePath,
            "--stage-root",
            stagedPythonRuntime.get().asFile.absolutePath,
            "--bridge",
            pythonNativeOutput.get().asFile.absolutePath,
            "--resolver",
            stagedPythonResolverBundle.get().asFile.absolutePath,
            "--apk",
            standardDebugApk.get().asFile.absolutePath,
        )
    }
}

val verifyStandardReleaseApkPythonRuntime by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the exact isolated CPython payload in the Standard release APK."
    dependsOn(
        "assembleStandardRelease",
        verifyStagedPythonRuntime,
        stagePythonResolverBundle,
        compileHansPythonNativeBridge,
    )
    inputs.file(pythonRuntimeLockFile)
    inputs.file(pythonNativePackagesLockFile)
    inputs.file(pythonRuntimeRoot.resolve("scripts/verify_runtime.py"))
    inputs.dir(stagedPythonRuntime)
    inputs.file(pythonNativeOutput)
    inputs.file(stagedPythonResolverBundle)
    inputs.file(standardReleaseApk)
    doFirst {
        commandLine(
            hansHostPython.get(),
            pythonRuntimeRoot.resolve("scripts/verify_runtime.py").absolutePath,
            "--lock",
            pythonRuntimeLockFile.absolutePath,
            "--stage-root",
            stagedPythonRuntime.get().asFile.absolutePath,
            "--bridge",
            pythonNativeOutput.get().asFile.absolutePath,
            "--resolver",
            stagedPythonResolverBundle.get().asFile.absolutePath,
            "--apk",
            standardReleaseApk.get().asFile.absolutePath,
        )
    }
}

val verifyPythonRuntimeSupplyChain by tasks.registering {
    group = "verification"
    description = "Runs staging, native, and exact debug/release APK CPython supply-chain verification."
    dependsOn(
        testPythonRuntimeHost,
        testPythonResolverHost,
        stagePythonResolverBundle,
        verifyStandardDebugApkPythonRuntime,
        verifyStandardReleaseApkPythonRuntime,
    )
}

val verifyStandardSourceBoundary by tasks.registering {
    group = "verification"
    description = "Rejects root modules, packages, permissions, APIs, commands, and dependencies at source level."

    val runtimeSources = fileTree("src/main") {
        include("**/*.aidl", "**/*.java", "**/*.json", "**/*.kt", "**/*.md", "**/*.xml")
    }
    inputs.files(runtimeSources)
    inputs.file(rootProject.file("settings.gradle.kts"))

    doLast {
        check(rootProject.subprojects.none { project ->
            project.path.contains("root-extension", ignoreCase = true)
        }) { "The permanent Standard build includes a root-extension project" }

        val forbiddenSourceMarkers = listOf(
            "ai.hans.root",
            "ai/hans/root",
            "ai.hans.standard.root",
            "ai/hans/standard/root",
            "android_root",
            "RootAccessRuntime",
            "RootExtension",
            "/system/bin/su",
            "/system/xbin/su",
            "su -c",
            "libsu",
            "topjohnwu",
            "android.content.pm.PackageInstaller",
            "REQUEST_INSTALL_PACKAGES",
        )
        runtimeSources.files.sortedBy { source -> source.path }.forEach { source ->
            val text = source.readText(Charsets.UTF_8)
            forbiddenSourceMarkers.forEach { marker ->
                check(marker !in text) {
                    "Permanent Standard runtime source ${source.relativeTo(projectDir)} contains $marker"
                }
            }
        }
        logger.lifecycle("Verified permanent Standard source boundary")
    }
}

fun verifyStandardStructuralBoundary(
    apk: java.io.File,
    mergedManifest: java.io.File,
    runtimeClasspath: String,
    variantLabel: String,
) {
    val manifest = mergedManifest.readText(Charsets.UTF_8)
    val forbiddenManifestMarkers = listOf(
        "ai.hans.root",
        "BIND_ROOT_EXTENSION",
        "android.permission.REQUEST_INSTALL_PACKAGES",
    )
    forbiddenManifestMarkers.forEach { marker ->
        check(marker !in manifest) {
            "$variantLabel merged manifest unexpectedly contains $marker"
        }
    }

    val dependencyComponents = configurations.getByName(runtimeClasspath)
        .incoming.resolutionResult.allComponents
        .map { component -> component.id.displayName }
    val forbiddenDependencyMarkers = listOf(
        "root-extension",
        "libsu",
        "topjohnwu",
    )
    forbiddenDependencyMarkers.forEach { marker ->
        check(dependencyComponents.none { component ->
            component.contains(marker, ignoreCase = true)
        }) {
            "$variantLabel runtime classpath unexpectedly contains $marker: $dependencyComponents"
        }
    }

    ZipFile(apk).use { zip ->
        check(zip.entries().asSequence().none { entry ->
            entry.name.contains("ai/hans/root") ||
                entry.name.contains("ai/hans/standard/root")
        }) { "$variantLabel APK contains a Root Extension class/resource entry" }

        val dex = zip.dexPayloads()
        check(dex.isNotEmpty()) { "$variantLabel APK contains no classes.dex payload" }
        val forbiddenDexMarkers = listOf(
            "Lai/hans/root/",
            "ai/hans/root/",
            "ai.hans.root",
            "Lai/hans/standard/root/",
            "ai/hans/standard/root/",
            "AndroidRootExtensionGateway",
            "RootAccessRuntime",
            "RootDynamicTools",
            "RootTypedDynamicTools",
            "android_root",
            "Root Extension",
            "Root-Erweiterung",
            "/system/bin/su",
            "/system/xbin/su",
            "su -c",
            "Landroid/content/pm/PackageInstaller",
        )
        forbiddenDexMarkers.forEach { marker ->
            check(dex.none { payload -> marker in payload }) {
                "$variantLabel classes.dex unexpectedly contains $marker"
            }
        }

        val promptAssets = listOf(
            "assets/hans/developer-instructions-standard.md",
            "assets/hans/live-voice-instructions.md",
            "assets/hans/bundled-plugins/hans-setup/skills/setup-hans-device/SKILL.md",
        ).joinToString("\n") { path -> zip.assetText(path) }
        listOf("android_root", "Root Extension", "Root-Erweiterung").forEach { marker ->
            check(marker !in promptAssets) {
                "$variantLabel prompt assets unexpectedly contain $marker"
            }
        }
    }
    logger.lifecycle("Verified structural Root Extension absence in $variantLabel")
}

val verifyStandardStructuralBoundary by tasks.registering {
    group = "verification"
    description = "Proves that permanent Standard debug contains no root contract, client, tools, prompt, or UI."
    dependsOn("assembleStandardDebug")
    inputs.file(standardDebugApk)
    inputs.file(standardDebugMergedManifest)

    doLast {
        verifyStandardStructuralBoundary(
            apk = standardDebugApk.get().asFile,
            mergedManifest = standardDebugMergedManifest.get().asFile,
            runtimeClasspath = "standardDebugRuntimeClasspath",
            variantLabel = "Standard debug",
        )
    }
}

val verifyStandardReleaseStructuralBoundary by tasks.registering {
    group = "verification"
    description = "Proves that permanent Standard release contains no root contract, client, tools, prompt, or UI."
    dependsOn("assembleStandardRelease", verifyStandardReleaseApkPythonRuntime)
    inputs.file(standardReleaseApk)
    inputs.file(standardReleaseMergedManifest)

    doLast {
        verifyStandardStructuralBoundary(
            apk = standardReleaseApk.get().asFile,
            mergedManifest = standardReleaseMergedManifest.get().asFile,
            runtimeClasspath = "standardReleaseRuntimeClasspath",
            variantLabel = "Standard release",
        )
    }
}

val verifyStandardStructuralBoundaries by tasks.registering {
    group = "verification"
    description = "Runs the permanent root-free Standard debug and release structural checks."
    dependsOn(
        verifyStandardSourceBoundary,
        verifyStandardStructuralBoundary,
        verifyStandardReleaseStructuralBoundary,
    )
}

val verifyEditionStructuralBoundaries by tasks.registering {
    group = "verification"
    description = "Compatibility alias for the permanent Standard structural boundary checks."
    dependsOn(verifyStandardStructuralBoundaries)
}

tasks.named("check") {
    dependsOn(verifyStandardStructuralBoundaries)
    dependsOn(verifyPythonRuntimeSupplyChain)
}

val verifyPinnedWebRtcAar by tasks.registering {
    group = "verification"
    description = "Verifies the exact pinned voice-only WebRTC AAR before Android builds."
    inputs.files(pinnedWebRtcAar)

    doLast {
        val candidates = pinnedWebRtcAar.files.filter { it.extension == "aar" }
        check(candidates.size == 1) {
            "Expected one pinned WebRTC AAR, resolved ${candidates.size}"
        }
        val aar = candidates.single()
        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == webRtcAarSha256) {
            "Pinned WebRTC AAR SHA-256 mismatch: expected $webRtcAarSha256, got $actual"
        }
        logger.lifecycle("Verified pinned WebRTC AAR sha256={}", actual)
    }
}

tasks.named("preBuild") {
    dependsOn(verifyPinnedWebRtcAar)
}

dependencies {
    // Deliberately pinned to the Android-16/API-36 compatible line. Newer
    // 2026 Compose artifacts already require API 37 and AGP 9.1.
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pure-Java, app-private Git implementation. Hans never shells out to a
    // system Git executable and never exposes repository filesystem paths.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.1.202607240634-r")
    // Voice-only, namespace-prefixed WebRTC build. The exact upstream source
    // revision and Maven Central AAR checksum are recorded beside the adapter
    // in voice/realtime/README.md.
    implementation(webRtcCoordinate)
    pinnedWebRtcAar(webRtcCoordinate)

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")

    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
