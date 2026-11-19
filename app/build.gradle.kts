import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.pocketagent"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "ru.pocketagent"
        minSdk = 26
        targetSdk = 34
        // -PvcOverride=N — тестовая сборка с меньшим versionCode (проверка автообновления)
        versionCode = (project.findProperty("vcOverride") as String?)?.toInt() ?: 10
        versionName = if (project.hasProperty("vcOverride")) "2.6-test" else "2.6"
        ndk { abiFilters += ((project.findProperty("abis") as String?) ?: "armeabi-v7a,arm64-v8a,x86_64").split(",") }  // -Pabis=x86_64 для эмулятора
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_static"
                )
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Лайт-сборка: ./gradlew assembleRelease -Plite  → APK без модели (скачивается при первом запуске)
    // -Plite  → без модели;  -Pbundle=vikhr → встроена Vikhr 0.5B;  без флагов → встроена MNN-Pocket (старый вариант)
    val lite = project.hasProperty("lite")
    val bundle = project.findProperty("bundle") as String?
    if (lite) {
        sourceSets.getByName("main").assets.setSrcDirs(listOf("src/lite/assets"))
    } else if (bundle == "vikhr") {
        sourceSets.getByName("main").assets.setSrcDirs(listOf("src/fullvikhr/assets"))
    }
    defaultConfig.buildConfigField("boolean", "LITE", lite.toString())
    // Манифест обновлений (update.json на Google Диске; id файла не меняется — содержимое обновляется на месте).
    // -PupdateUrl= (пусто) — сборка без автообновления.
    // -Prustore — то же + без REQUEST_INSTALL_PACKAGES (сборка для магазина; обычная не меняется).
    val rustore = project.hasProperty("rustore")
    val updateUrl = if (rustore) "" else ((project.findProperty("updateUrl") as String?) ?: "https://drive.google.com/uc?export=download&id=1Ndu-3ihhgLO_xJYRdksAKHvlE75TjJxj")
    defaultConfig.manifestPlaceholders["netcfg"] = if (findProperty("localUpdateTest") != null) "@xml/net_localtest" else "@xml/net_default"
    defaultConfig.manifestPlaceholders["installPermission"] = if (updateUrl.isNotBlank()) "android.permission.REQUEST_INSTALL_PACKAGES" else "android.permission.VIBRATE"
    defaultConfig.buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateUrl\"")
    defaultConfig.buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://obbi-man.github.io/PocketAgent/\"")
    buildFeatures { buildConfig = true }

    // Модель хранится в assets без сжатия (быстрое копирование, без распаковки zip)
    androidResources {
        noCompress += listOf("gguf")
    }

    // Данные ключа подписи читаются из keystore.properties (не входит в репозиторий, см. README)
    val ksProps = Properties().also { pr ->
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { pr.load(it) }
    }
    signingConfigs {
        create("selfsigned") {
            storeFile = file("../" + ksProps.getProperty("storeFile", "pocketagent.keystore"))
            storePassword = ksProps.getProperty("storePassword", "")
            keyAlias = ksProps.getProperty("keyAlias", "")
            keyPassword = ksProps.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ключ подписи не входит в публичные исходники; без него release-сборка подписывается debug-ключом
            signingConfig = if (file("../pocketagent.keystore").exists() && ksProps.getProperty("storePassword") != null) signingConfigs.getByName("selfsigned") else signingConfigs.getByName("debug")
        }
        debug {
            // native тоже в Release-оптимизации (см. CMAKE_BUILD_TYPE выше)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }  // .so сжаты → лайт-APK меньше при 3 ABI
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // текст из PDF (BouncyCastle не нужен: зашифрованные сертификатом PDF не поддерживаем)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
