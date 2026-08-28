import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.rikka.tools.refine)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hiltAndroid)
}

android {
    val buildTime = System.currentTimeMillis()
    namespace = "com.xiaochuang.freeform"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaochuang.freeform"
        minSdk = 33
        targetSdk = 36
        // 纯数字版本号：每次提交自动递增（versionCode/versionName = git 提交总数）
        versionCode = gitCommitCount
        versionName = gitCommitCount.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "BUILD_TIME", buildTime.toString())
    }
    packaging {
        resources.excludes.addAll(
            arrayOf(
                "META-INF/**",
                "kotlin/**"
            )
        )
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // CI 产物直接用 debug 签名以便安装；发布正式版时替换为自己的 keystore
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        languageVersion = "2.0"
    }
    buildFeatures {
        viewBinding = true
        aidl = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.wear)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.preference.ktx)

    compileOnly(project(":android-stub"))
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.hidden.compat)

    //never upgrade until new extension function
    //noinspection GradleDependency
    implementation(libs.ezxhelper)
    compileOnly(libs.xposed.api)

    //lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    //flexbox
    implementation(libs.flexbox)

    //dynamicanimation
    implementation(libs.androidx.dynamicanimation.ktx)

    //gson
    implementation(libs.gson)

    //material
    implementation(libs.material)

    //glide
    implementation (libs.glide)

    // byte buddy
    implementation(libs.byte.buddy.android)

    //lifecycle service
    implementation(libs.androidx.lifecycle.service)

    //room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    //hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)
}

val gitCommitCount: Int
    get() {
        val out = ByteArrayOutputStream()
        val cmd = exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        return if (cmd.exitValue == 0)
            out.toString().trim().toIntOrNull() ?: 1
        else
            1
    }