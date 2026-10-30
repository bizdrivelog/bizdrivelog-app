import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.bizdrivelog"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.bizdrivelog"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // 릴리스 서명: 키스토어·비밀번호는 저장소 밖에 둔다 (기본 ~/.bizdrivelog-keys/keystore.properties, BIZDRIVELOG_KEYSTORE_PROPS로 변경).
    // 파일이 없으면 서명 없이 빌드된다 (app-release-unsigned.apk)
    val keystoreProps = file(System.getenv("BIZDRIVELOG_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/.bizdrivelog-keys/keystore.properties")
    val releaseSigning = if (keystoreProps.isFile) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    } else null

    buildTypes {
        release {
            signingConfig = releaseSigning
        }
    }

    // AAOS Car API (android.car.jar in platforms/android-35/optional)
    useLibrary("android.car")

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    // xlsx 생성 (Apache POI 대신 가벼운 쓰기 전용 라이브러리)
    implementation("org.dhatim:fastexcel:0.18.4")

    // 휴대폰으로 받기: 앱 안 일회용 HTTP 서버 + QR (둘 다 의존성 없는 소형 라이브러리)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("io.nayuki:qrcodegen:1.8.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
}
