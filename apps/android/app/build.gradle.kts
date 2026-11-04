import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "com.reallanguage.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.reallanguage.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 106
        versionName = "1.26.0-trial.20261104.106"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        val apiBaseUrl = providers.gradleProperty("REAL_LANGUAGE_API_BASE_URL")
            .orElse("https://api.invalid")
            .get()
        val apiFallbackBaseUrl = providers.gradleProperty("REAL_LANGUAGE_API_FALLBACK_BASE_URL")
            .orElse("")
            .get()
        val apiNsdServiceType = providers.gradleProperty("REAL_LANGUAGE_API_NSD_SERVICE_TYPE")
            .orElse("")
            .get()
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "API_FALLBACK_BASE_URL", "\"$apiFallbackBaseUrl\"")
        buildConfigField("String", "API_NSD_SERVICE_TYPE", "\"$apiNsdServiceType\"")
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// A local debug APK must not silently ship yesterday's Wi-Fi fallback.
// Run even when compilation is up-to-date: DHCP can change between builds.
val verifyLocalApiBuildConfig = tasks.register("verifyLocalApiBuildConfig") {
    val primary = providers.gradleProperty("REAL_LANGUAGE_API_BASE_URL").orElse("")
    val fallback = providers.gradleProperty("REAL_LANGUAGE_API_FALLBACK_BASE_URL").orElse("")
    val discovery = providers.gradleProperty("REAL_LANGUAGE_API_NSD_SERVICE_TYPE").orElse("")
    doLast {
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true) &&
            primary.get().contains(".local:")
        ) {
            val process = ProcessBuilder("/usr/sbin/ipconfig", "getifaddr", "en0")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            check(process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "无法读取当前 Wi-Fi 地址，请先运行 tooling/check-local-api-connection.sh。"
            }
            val currentAddress = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.exitValue() == 0 && currentAddress.isNotBlank()) {
                val runtimeDiscoveryEnabled = discovery.get() == "_reallanguage._tcp."
                check(runtimeDiscoveryEnabled || fallback.get() == "http://$currentAddress:3000") {
                    "拒绝生成备用地址过期的 APK：当前 Wi-Fi 为 $currentAddress，配置为 ${fallback.get()}。" +
                        "请启用 REAL_LANGUAGE_API_NSD_SERVICE_TYPE 或更新备用地址，再运行连接检查。"
                }
            }
        }
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyLocalApiBuildConfig)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val viewModelClasses = provider {
    zipTree(
        layout.buildDirectory.file(
            "intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar",
        ).get().asFile,
    ).matching {
        include("com/reallanguage/app/feature/auth/presentation/AuthViewModel*.class")
        include("com/reallanguage/app/core/navigation/AppCoordinatorViewModel*.class")
        include("com/reallanguage/app/feature/auth/presentation/EmailVerificationViewModel*.class")
        include("com/reallanguage/app/feature/auth/presentation/AgreementDetailViewModel*.class")
        include("com/reallanguage/app/feature/auth/presentation/PasswordResetViewModel*.class")
        include("com/reallanguage/app/feature/settings/presentation/InitialSettingsViewModel*.class")
        include("com/reallanguage/app/feature/settings/presentation/SubsequentSettingsViewModel*.class")
        include("com/reallanguage/app/feature/settings/presentation/SettingsEffectScopeViewModel*.class")
        include("com/reallanguage/app/feature/home/presentation/DailyReminderIntroViewModel*.class")
        include("com/reallanguage/app/feature/home/presentation/IncompleteLearningViewModel*.class")
        include("com/reallanguage/app/feature/home/presentation/MessagesViewModel*.class")
        include("com/reallanguage/app/feature/home/presentation/AccountViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/RecordingIntroViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/ActiveRecordingViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/RecordingConfirmationViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/RecordingDeletionViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/RecordingTranscriptionViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/TranscriptResultViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/TranscriptionFailureViewModel*.class")
        include("com/reallanguage/app/feature/recording/presentation/MicrophonePermissionViewModel*.class")
        include("com/reallanguage/app/feature/scene/presentation/SceneHomeViewModel*.class")
        include("com/reallanguage/app/feature/scene/presentation/SceneTypeViewModel*.class")
        include("com/reallanguage/app/feature/scene/presentation/SceneQuestionsViewModel*.class")
        include("com/reallanguage/app/feature/scene/presentation/SceneAbandonViewModel*.class")
        include("com/reallanguage/app/feature/scene/presentation/SceneGenerationViewModel*.class")
        include("com/reallanguage/app/feature/scene/presentation/SceneChineseReviewViewModel*.class")
        include("com/reallanguage/app/feature/diary/presentation/DiaryInputViewModel*.class")
        include("com/reallanguage/app/feature/safety/presentation/SafetyCheckViewModel*.class")
        include("com/reallanguage/app/feature/safety/presentation/SafetyHandlingViewModel*.class")
        include("com/reallanguage/app/feature/formation/presentation/FocusSelectionViewModel*.class")
        include("com/reallanguage/app/feature/formation/presentation/ChapterFormationViewModel*.class")
        include("com/reallanguage/app/feature/formation/presentation/EnglishConfirmationViewModel*.class")
        include("com/reallanguage/app/feature/formation/presentation/LearningTargetViewModel*.class")
        include("com/reallanguage/app/feature/formation/presentation/FinalFixViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/ExplanationReadingViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/ReadSpeakViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/HandwritingCopyViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/AudioDictationViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/FullSentenceSpeakingViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/PatternSpeakingViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/CompleteWritingViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/SceneResponseViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/LearningExitViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/ChapterResultViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/ChapterSelectionViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/ChapterAbandonViewModel*.class")
        include("com/reallanguage/app/feature/learning/presentation/CompletedStepReviewViewModel*.class")
        include("com/reallanguage/app/feature/review/presentation/TodayReviewViewModel*.class")
        include("com/reallanguage/app/feature/review/presentation/ReviewChapterViewModel*.class")
        include("com/reallanguage/app/feature/review/presentation/ReviewExitViewModel*.class")
        include("com/reallanguage/app/feature/review/presentation/ReviewResultViewModel*.class")
        include("com/reallanguage/app/feature/knowledge/presentation/VocabularyBookViewModel*.class")
        include("com/reallanguage/app/feature/knowledge/presentation/PatternBookViewModel*.class")
        include("com/reallanguage/app/feature/knowledge/presentation/PatternRemovalViewModel*.class")
        include("com/reallanguage/app/feature/billing/presentation/AiBillingViewModel*.class")
        include("com/reallanguage/app/feature/billing/presentation/RechargeViewModel*.class")
        include("com/reallanguage/app/feature/billing/presentation/InsufficientBalanceViewModel*.class")
        include("com/reallanguage/app/feature/billing/presentation/RefundViewModel*.class")
        include("com/reallanguage/app/feature/error/presentation/ConnectionErrorViewModel*.class")
        include("com/reallanguage/app/feature/error/presentation/ServiceFailureViewModel*.class")
    }
}

val viewModelExecutionData = layout.buildDirectory.file(
    "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
)

tasks.register<JacocoReport>("viewModelCoverageReport") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(viewModelClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(viewModelExecutionData)
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

tasks.register<JacocoCoverageVerification>("viewModelCoverageVerification") {
    dependsOn("testDebugUnitTest")
    classDirectories.setFrom(viewModelClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(viewModelExecutionData)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("viewModelCoverageReport", "viewModelCoverageVerification")
}
