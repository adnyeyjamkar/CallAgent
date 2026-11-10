import java.net.URI
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.callagent"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.callagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0-offline"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Offline speech recognition (Kaldi). No network code.
    implementation(libs.vosk.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// ---------------------------------------------------------------------------------------------
// The Vosk acoustic model (~37 MB) is not stored in git. It is downloaded once, at BUILD time,
// into app/src/main/assets/model-en-in and then packaged inside the APK. The installed app
// never downloads anything.
//
// Use another language/accent with e.g.:
//   ./gradlew assembleDebug -PvoskModel=vosk-model-small-en-us-0.15
// ---------------------------------------------------------------------------------------------
abstract class DownloadVoskModel : DefaultTask() {
    @get:Input
    abstract val modelName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun download() {
        val name = modelName.get()
        val zip = File(temporaryDir, "$name.zip")
        logger.lifecycle("Downloading speech model $name ...")
        URI("https://alphacephei.com/vosk/models/$name.zip").toURL().openStream().use { input ->
            zip.outputStream().use { output -> input.copyTo(output) }
        }
        val target = outputDir.get().asFile
        files.delete { delete(target) }
        files.copy {
            from(archives.zipTree(zip)) {
                // Strip the top-level "<model-name>/" folder inside the archive.
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                includeEmptyDirs = false
            }
            into(target)
        }
        // Vosk's StorageService uses this marker to know when the unpacked copy is stale.
        File(target, "uuid").writeText(name)
        zip.delete()
    }
}

val downloadVoskModel = tasks.register<DownloadVoskModel>("downloadVoskModel") {
    group = "build setup"
    description = "Downloads the offline speech-recognition model into the app's assets."
    modelName.set(providers.gradleProperty("voskModel").orElse("vosk-model-small-en-in-0.4"))
    outputDir.set(layout.projectDirectory.dir("src/main/assets/model-en-in"))
}

tasks.named("preBuild") { dependsOn(downloadVoskModel) }
