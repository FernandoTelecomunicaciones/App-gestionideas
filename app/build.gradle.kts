import java.time.Duration
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing (DECISIONS D-35). The production keystore is NEVER in the repo. It is supplied either by
// `keystore.properties` at the repository root (git-ignored) or by environment variables, and only for the
// release build. With neither, `assembleRelease` still builds (unsigned), so CI needs no secrets to compile.
//   keystore.properties: storeFile=..., storePassword=..., keyAlias=..., keyPassword=...
//   environment:         AHORA_KEYSTORE_FILE, AHORA_KEYSTORE_PASSWORD, AHORA_KEY_ALIAS, AHORA_KEY_PASSWORD
// Providers (not plain File reads) so the configuration cache tracks the file and the variables as inputs.
val signingFile = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
val signingProps = Properties().apply { signingFile.asText.orNull?.let { load(it.reader()) } }
fun signingValue(propertyName: String, envName: String): String? =
    (signingProps.getProperty(propertyName) ?: providers.environmentVariable(envName).orNull)
        ?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingValue("storeFile", "AHORA_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "AHORA_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "AHORA_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "AHORA_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = releaseSigningValues.all { it != null }
// Half a configuration is a mistake, not "no signing": fail loudly instead of shipping an unsigned APK by accident.
// (Names only; a value is never printed.)
if (!hasReleaseSigning && releaseSigningValues.any { it != null }) {
    throw GradleException(
        "Release signing is partly configured. Provide all of storeFile, storePassword, keyAlias, keyPassword " +
            "(keystore.properties) or AHORA_KEYSTORE_FILE, AHORA_KEYSTORE_PASSWORD, AHORA_KEY_ALIAS, " +
            "AHORA_KEY_PASSWORD - or none of them for an unsigned build.",
    )
}

android {
    namespace = "com.fernando.ahora"
    // compileSdk 37 is required by current stable androidx (Compose 1.12, core 1.19); targetSdk stays 36
    // because compileSdk only unlocks APIs, targetSdk opts into runtime behaviour changes.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fernando.ahora"
        minSdk = 26
        targetSdk = 36
        // Release policy: DECISIONS D-35. versionCode only ever goes up (+1 per distributed build, never reused).
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// A hung test must fail the build loudly instead of stalling it (a self-deadlocking test once did).
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(8))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
