import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// We can't use the `plugins {}` block here because the plugin marker
// artifacts are not in the local cache. Falling back to the classic
// buildscript classpath approach lets us apply the gradle plugins
// directly from the cached jars.
//
// Plugins applied (cached versions pinned):
//   - org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10
//   - com.android.tools.build:gradle:9.2.1
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("com.android.tools.build:gradle:9.2.1")
    }
    // The kotlin plugin transitively pulls in `error_prone_annotations:2.27.0`,
    // but only 2.28.0 is in the local cache. Pin the resolved version
    // so we don't have to hit the network for one annotation jar.
    configurations.classpath {
        resolutionStrategy {
            force("com.google.errorprone:error_prone_annotations:2.28.0")
        }
    }
}

apply(plugin = "org.jetbrains.kotlin.multiplatform")
apply(plugin = "com.android.library")
apply(plugin = "maven-publish")
apply(plugin = "signing")

// Read the published version from gradle/libs.versions.toml so the app
// can keep its own version catalog entry (`blockprint = "..."`) in lockstep
// with the lib. We use the manual VersionCatalogsExtension lookup because
// the type-safe `libs.versions.blockprint` accessor requires the
// `kotlin-dsl` plugin, which we can't apply here.
val versionCatalog: VersionCatalog = extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")
val libVersion: String = versionCatalog.findVersion("blockprint").get().requiredVersion

group = "io.github.moxisuki"
version = libVersion

repositories {
    // AGP needs `google()` to resolve aapt2, agp, and the Android SDK
    // artifacts that the `androidTarget()` declares via the Android plugin.
    // Without this, packageDebugResources fails with
    // "Could not find com.android.tools.build:aapt2:<version>" when a
    // composite-build consumer (e.g. Litematic2GLB) tries to wire up the
    // included build, because the consumer's repositories don't propagate
    // to the included build's tasks.
    google()
    mavenCentral()
}

// Configure KMP targets. `apply()` style doesn't register type-safe
// accessors in the precompiled script, so we use `configure<>` to wire
// up the Kotlin Multiplatform and Android Library extensions. At runtime
// the extensions are identical to what the type-safe DSL would expose.
configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    jvmToolchain(21)

    jvm()
    androidTarget()

    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val jvmMain by getting
        val jvmTest by getting
        val androidMain by getting
        val androidUnitTest by getting

        // Test dependencies are JVM-only. Android unit tests would
        // require Robolectric for the image-loading code; for now
        // we only run the parser test suite on the JVM target.
        dependencies {
            "jvmTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
            "jvmTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }
    }
}

configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "io.github.moxisuki.blockprint.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // The library has no Android resources or manifest, only Kotlin code.
        buildConfig = false
    }
}

// ── Publishing ────────────────────────────────────────────────────
// Local:  ./gradlew publishToMavenLocal
// Remote: ./gradlew publishAllPublicationsToSonatypeRepository
//
// Sonatype credentials: OSSRH_USERNAME / OSSRH_PASSWORD env vars
// GPG signing:          ORG_GRADLE_PROJECT_signingKey (armored base64)
//                       ORG_GRADLE_PROJECT_signingPassword
configure<PublishingExtension> {
    repositories {
        maven {
            name = "Sonatype"
            url = uri(
                if (libVersion.endsWith("-SNAPSHOT"))
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            )
            credentials {
                username = System.getenv("OSSRH_USERNAME")
                password = System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

// ── Wire publications + conditional signing ───────────────────────
// Signing reads GPG key from a file (too large for env vars).
// Put armored key in ~/.gradle/signing.key and passphrase in
// ~/.gradle/signing.password.
// CI: set ORG_GRADLE_PROJECT_signingKey / ORG_GRADLE_PROJECT_signingPassword.

fun readFileOrEnv(fileName: String, envVar: String): String? {
    val homeDir = System.getProperty("user.home")
    val file = java.io.File(homeDir, ".gradle/$fileName")
    if (file.isFile) return file.readText().trim()
    return System.getenv(envVar)
}

val signingKeyFile = readFileOrEnv("signing.key", "ORG_GRADLE_PROJECT_signingKey")
val signingPassFile = readFileOrEnv("signing.password", "ORG_GRADLE_PROJECT_signingPassword")

val hasSigning = !signingKeyFile.isNullOrBlank()
if (hasSigning) {
    configure<SigningExtension> {
        useInMemoryPgpKeys(signingKeyFile, signingPassFile)
    }
}

afterEvaluate {
    configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            pom {
                name.set("BlockPrint Core")
                description.set(
                    "Zero-dependency Kotlin Multiplatform library for " +
                    "parsing Minecraft litematic / schematic files and " +
                    "generating real-time GLB 3D previews."
                )
                url.set("https://github.com/moxisuki/blockprint-core")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("moxisuki")
                        name.set("moxisuki")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/moxisuki/blockprint-core.git")
                    developerConnection.set("scm:git:ssh://github.com/moxisuki/blockprint-core.git")
                    url.set("https://github.com/moxisuki/blockprint-core")
                }
            }
        }
    }

    if (hasSigning) {
        configure<SigningExtension> {
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
}

// Auto-generate BlockPrintCoreVersion.kt from the version catalog
val versionOutputDir = layout.buildDirectory.dir("generated/version/main")
val generateVersionFile = tasks.register("generateVersionFile") {
    outputs.dir(versionOutputDir)
    doLast {
        val file = versionOutputDir.get().file("io/github/moxisuki/blockprint/core/BlockPrintCoreVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("""
            package io.github.moxisuki.blockprint.core
            /** Auto-generated from gradle/libs.versions.toml — do not edit manually. */
            const val BLOCKPRINT_CORE_VERSION = "$libVersion"
        """.trimIndent())
    }
}

configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(versionOutputDir)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    maxHeapSize = "4g"
}
