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
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("com.android.tools.build:gradle:9.2.1")
        classpath("io.github.gradle-nexus:publish-plugin:2.0.0")
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
apply(plugin = "io.github.gradle-nexus.publish-plugin")

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
    androidTarget {
        publishLibraryVariants("release")
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting
        val jvmMain by getting
        val jvmTest by getting
        val androidMain by getting
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

dependencies {
    "jvmTestImplementation"("junit:junit:4.13.2")
}

apply(from = "gradle/create-static-assemblies.gradle.kts")

tasks.register("writeCreateModelBakerProbe") {
    group = "model extraction"
    description = "Prepares the standalone Create/NeoForge model-baker tool module."
    dependsOn(":tools:create-model-baker:writeCreateModelBakerInitScript")
}

tasks.register("runCreateModelBakerProbe") {
    group = "model extraction"
    description = "Runs the standalone model-baker classpath probe in the local Create checkout."
    dependsOn(":tools:create-model-baker:runCreateModelBakerProbe")
}

tasks.register("runCreateModelBakerDataProbe") {
    group = "model extraction"
    description = "Runs the standalone model-baker as a headless NeoForge data-run mod in the local Create checkout."
    dependsOn(":tools:create-model-baker:runCreateModelBakerDataProbe")
}

tasks.register("runCreateModelBakerClientProbe") {
    group = "model extraction"
    description = "Runs the standalone model-baker in a real NeoForge client lifecycle and exits after model baking."
    dependsOn(":tools:create-model-baker:runCreateModelBakerClientProbe")
}

tasks.register("runCreateModelBakerClientExport") {
    group = "model extraction"
    description = "Exports baked static model manifests from a real NeoForge client lifecycle. Defaults to -Dblockprint.baker.namespaces=create."
    dependsOn(":tools:create-model-baker:runCreateModelBakerClientExport")
}

tasks.register<JavaExec>("writeBakerBlockstateFile") {
    group = "model extraction"
    description = "Reads a blueprint palette and writes a blockstate selector file for the model baker. Pass -PblueprintFile=... [-PblockstateFile=...]."
    dependsOn("jvmJar")

    val runtimeClasspath = configurations.named("jvmRuntimeClasspath")
    classpath(tasks.named("jvmJar"), runtimeClasspath)
    mainClass.set("io.github.moxisuki.blockprint.core.tools.WriteBakerBlockstateFileKt")

    doFirst {
        val blueprintFile = (findProperty("blueprintFile") as? String)
            ?: System.getProperty("blockprint.blueprintFile")
            ?: error("Missing blueprint input. Pass -PblueprintFile=C:\\path\\to\\blueprint.nbt")
        val blockstateFile = (findProperty("blockstateFile") as? String)
            ?: System.getProperty("blockprint.blockstateFile")
            ?: layout.buildDirectory.file("blockprint-model-baker/palette.blockstates.txt").get().asFile.absolutePath
        val includeAir = ((findProperty("includeAir") as? String)
            ?: System.getProperty("blockprint.includeAir")
            ?: "false").toBoolean()
        val allPalette = ((findProperty("allPalette") as? String)
            ?: System.getProperty("blockprint.allPalette")
            ?: "false").toBoolean()

        args = mutableListOf("--input", blueprintFile, "--output", blockstateFile).also {
            if (includeAir) it.add("--include-air")
            if (allPalette) it.add("--all-palette")
        }
    }
}

// ── Publishing ────────────────────────────────────────────────────
// Local:  ./gradlew publishToMavenLocal
// Remote: ./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
//
// Credentials in ~/.gradle/gradle.properties:
//   sonatypeUsername=<user token>
//   sonatypePassword=<user token password>
// Token: https://central.sonatype.com → Account → User Token
configure<io.github.gradlenexus.publishplugin.NexusPublishExtension> {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}

// ── Wire publications + conditional signing ───────────────────────
// ── Signing ────────────────────────────────────────────────────────
// Armored key in ~/.gradle/signing.key, passphrase in ~/.gradle/signing.password.
// CI: set ORG_GRADLE_PROJECT_signingKey / ORG_GRADLE_PROJECT_signingPassword.

fun readFileOrEnv(fileName: String, envVar: String): String? {
    val f = java.io.File(System.getProperty("user.home"), ".gradle/$fileName")
    if (f.isFile) return f.readText().trim()
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

// ── Javadoc JAR (Maven Central requires it; KMP has no unified javadoc) ──
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    configure<PublishingExtension> {

        publications.withType<MavenPublication>().configureEach {
            // Only attach javadoc to the jvm publication (KotlinMultiplatform
            // is metadata-only and doesn't need it).
            if (name == "jvm") {
                artifact(javadocJar)
            }

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
    inputs.property("version", libVersion)
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

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    dependsOn(generateVersionFile)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
tasks.matching { it.name.lowercase().endsWith("sourcesjar") }.configureEach {
    dependsOn(generateVersionFile)
}

configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(versionOutputDir)
        }
    }
}

