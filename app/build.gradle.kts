import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

tasks.register("fetchAndBuildWebApp") {
    description = "Clones/updates the weather web app repo and builds it into assets/www"
    group = "build"

    val repoUrl = "https://github.com/StormView-Project/SkyView.git"
    val webAppDir = file("${rootProject.projectDir}/web-app-source")
    val assetsWwwDir = file("${projectDir}/src/main/assets/www")
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    fun run(vararg cmd: String, dir: File = webAppDir) {
        val command = if (isWindows) listOf("cmd", "/c") + cmd.toList() else cmd.toList()
        val pb = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
        val process = pb.start()
        process.inputStream.bufferedReader().forEachLine { println(it) }
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("Command failed (exit $exit): ${command.joinToString(" ")}")
    }

    doLast {
        // ---- 1. Clone or pull ----
        // Validate the existing folder actually has package.json before trusting it.
        // If not, wipe and re-clone.
        val packageJson = file("${webAppDir}/package.json")
        if (webAppDir.exists() && !packageJson.exists()) {
            println("web-app-source exists but package.json is missing — wiping and re-cloning...")
            webAppDir.deleteRecursively()
        }

        if (!webAppDir.exists()) {
            println("Cloning weather web app...")
            run("git", "clone", repoUrl, webAppDir.absolutePath, dir = rootProject.projectDir)
        } else {
            println("Pulling latest weather web app...")
            run("git", "pull")
        }

        // Sanity check
        if (!packageJson.exists()) {
            throw GradleException("Clone succeeded but package.json still missing at ${packageJson.absolutePath}")
        }

        // ---- 2. Read VITE_ keys from local.properties and write .env ----
        val localProps = Properties()
        val localPropsFile = file("${rootProject.projectDir}/local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { localProps.load(it) }
        }
        val viteEnvLines = localProps.entries
            .filter { (it.key as String).startsWith("VITE_") }
            .joinToString("\n") { "${it.key}=${it.value}" }
        if (viteEnvLines.isNotEmpty()) {
            file("${webAppDir}/.env").writeText(viteEnvLines)
            println("Wrote .env from local.properties")
        } else {
            println("No VITE_ keys found in local.properties — skipping .env")
        }

        // ---- 3. Patch vite.config.js ----
        file("${webAppDir}/vite.config.js").writeText(
            """
            import { defineConfig } from 'vite'
            import react from '@vitejs/plugin-react'

            export default defineConfig({
              plugins: [react()],
              base: './',
            })
            """.trimIndent()
        )
        println("Patched vite.config.js")

        // ---- 4. npm install + build ----
        val npmBin = listOf("/usr/local/bin/npm", "/usr/bin/npm", "npm")
            .firstOrNull { it == "npm" || file(it).exists() } ?: "npm"

        println("Using npm at: $npmBin")
        println("Running npm install...")
        run(npmBin, "install")
        println("Running npm run build...")
        run(npmBin, "run", "build")

        // ---- 5. Sync dist/ → assets/www/ ----
        val distDir = file("${webAppDir}/dist")
        if (!distDir.exists()) {
            throw GradleException("dist/ not found after build at ${distDir.absolutePath}")
        }
        println("Copying dist/ → assets/www/...")
        if (assetsWwwDir.exists()) assetsWwwDir.deleteRecursively()
        assetsWwwDir.mkdirs()
        distDir.copyRecursively(assetsWwwDir, overwrite = true)
        println("Done — web app ready at ${assetsWwwDir.absolutePath}")
    }
}

afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn("fetchAndBuildWebApp")
    }
}

android {
    namespace = "com.example.stormview"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.stormview"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.play.services.location)
}