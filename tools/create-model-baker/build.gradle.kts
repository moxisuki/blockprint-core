import java.io.File

// Standalone build-time model baker harness.
//
// This module is intentionally outside core source sets. It is a JVM/Gradle
// tooling module only: it can depend on / run Minecraft + NeoForge + mods at
// build time, then emit baked mesh artifacts for blockprint-core to consume.

val createSourceDirProvider = providers.gradleProperty("createSourceDir")
    .orElse(providers.environmentVariable("CREATE_SOURCE_DIR"))
    .orElse(
        providers.provider {
            File(System.getProperty("user.home"), ".codex/repo-cache/model-adapter-research/Create")
                .absolutePath
        },
    )

val toolSourceDir = layout.projectDirectory.dir("src/main/java")
val toolResourcesDir = layout.projectDirectory.dir("src/main/resources")
val initScript = layout.buildDirectory.file("blockprint-create-model-baker.init.gradle")

tasks.register("writeCreateModelBakerInitScript") {
    group = "model extraction"
    description = "Writes the temporary Gradle init script used to load the model-baker tool as a NeoForge dev mod."

    inputs.dir(toolSourceDir)
    inputs.dir(toolResourcesDir)
    outputs.file(initScript)

    doLast {
        val initFile = initScript.get().asFile
        initFile.parentFile.mkdirs()
        initFile.writeText(
            """
            gradle.beforeProject { root ->
                if (root != gradle.rootProject) {
                    return
                }
                root.plugins.withId("net.neoforged.moddev") {
                    def injectedSrc = new File(System.getProperty("blockprint.baker.srcDir"))
                    def injectedResources = new File(System.getProperty("blockprint.baker.resourcesDir"))
                    root.logger.lifecycle("[BlockPrintModelBaker] Injecting tool source: " + injectedSrc)
                    root.logger.lifecycle("[BlockPrintModelBaker] Injecting tool resources: " + injectedResources)

                    // Override optional mod properties to avoid Modrinth 503 failures
                    root.ext.farmers_delight_enable = "false"
                    root.ext.cc_tweaked_enable = "false"
                    // Exclude journeymap from all configurations to avoid Modrinth 503 failures
                    root.configurations.all {
                        exclude group: "maven.modrinth", module: "journeymap"
                        exclude group: "info.journeymap"
                    }
                    // Use proxy for remaining external Maven repositories
                    System.setProperty("http.proxyHost", "127.0.0.1")
                    System.setProperty("http.proxyPort", "7890")
                    System.setProperty("https.proxyHost", "127.0.0.1")
                    System.setProperty("https.proxyPort", "7890")

                    root.sourceSets {
                        blockprintBaker {
                            java.srcDir(injectedSrc)
                            resources.srcDir(injectedResources)
                            compileClasspath += root.sourceSets.main.output + root.sourceSets.main.compileClasspath
                            runtimeClasspath += output + compileClasspath + root.sourceSets.main.runtimeClasspath
                        }
                    }

                    root.neoForge.mods {
                        blockprint_model_baker {
                            sourceSet(root.sourceSets.blockprintBaker)
                        }
                    }

                    root.neoForge.runs {
                        blockprintBakerClient {
                            client()
                            gameDirectory = root.file("run/blockprint-baker-client")
                            systemProperty("blockprint.baker.exitAfterBake", "true")
                            systemProperty("blockprint.baker.outputDir", new File(root.buildDir, "blockprint-model-baker/generated").absolutePath)
                            def requestedBlocks = System.getProperty("blockprint.baker.blocks")
                            if (requestedBlocks != null && !requestedBlocks.isBlank()) {
                                systemProperty("blockprint.baker.blocks", requestedBlocks)
                            }
                            def requestedNamespaces = System.getProperty("blockprint.baker.namespaces")
                            if (requestedNamespaces != null && !requestedNamespaces.isBlank()) {
                                systemProperty("blockprint.baker.namespaces", requestedNamespaces)
                            }
                            def requestedModIds = System.getProperty("blockprint.baker.modids")
                            if (requestedModIds != null && !requestedModIds.isBlank()) {
                                systemProperty("blockprint.baker.modids", requestedModIds)
                            }
                            def requestedBlockstates = System.getProperty("blockprint.baker.blockstates")
                            if (requestedBlockstates != null && !requestedBlockstates.isBlank()) {
                                systemProperty("blockprint.baker.blockstates", requestedBlockstates)
                            }
                            def requestedBlockstateFile = System.getProperty("blockprint.baker.blockstateFile")
                            if (requestedBlockstateFile != null && !requestedBlockstateFile.isBlank()) {
                                systemProperty("blockprint.baker.blockstateFile", requestedBlockstateFile)
                            }
                            def requestedManifestName = System.getProperty("blockprint.baker.manifestName")
                            if (requestedManifestName != null && !requestedManifestName.isBlank()) {
                                systemProperty("blockprint.baker.manifestName", requestedManifestName)
                            }
                            def writeCombinedManifest = System.getProperty("blockprint.baker.writeCombinedManifest")
                            if (writeCombinedManifest != null && !writeCombinedManifest.isBlank()) {
                                systemProperty("blockprint.baker.writeCombinedManifest", writeCombinedManifest)
                            }
                            def writeProbe = System.getProperty("blockprint.baker.writeProbe")
                            if (writeProbe != null && !writeProbe.isBlank()) {
                                systemProperty("blockprint.baker.writeProbe", writeProbe)
                            }
                            [
                                "blockprint.baker.captureBlockDispatcher",
                                "blockprint.baker.captureBlockEntityRenderers",
                                "blockprint.baker.captureFlywheelVisuals",
                                "blockprint.baker.deferUntilClientTick",
                                "blockprint.baker.loadDefaultServerConfigs",
                                "blockprint.baker.atlasSpriteUvEpsilon",
                                "blockprint.baker.rendererFallbackTexture"
                            ].each { propertyName ->
                                def propertyValue = System.getProperty(propertyName)
                                if (propertyValue != null && !propertyValue.isBlank()) {
                                    systemProperty(propertyName, propertyValue)
                                }
                            }
                            systemProperty("mixin.debug.verbose", "false")
                            systemProperty("mixin.debug.export", "false")
                            programArguments.addAll(
                                "--width", "1",
                                "--height", "1"
                            )
                        }

                        blockprintBakerData {
                            data()
                            gameDirectory = root.file("run/blockprint-baker-data")
                            systemProperty("blockprint.baker.exitAfterProbe", "true")
                            systemProperty("blockprint.baker.outputDir", new File(root.buildDir, "blockprint-model-baker/generated").absolutePath)
                            programArguments.addAll(
                                "--mod", "blockprint_model_baker",
                                "--all",
                                "--output", new File(root.buildDir, "blockprint-model-baker/generated").absolutePath,
                                "--existing", root.file("src/main/resources").absolutePath
                            )
                        }
                    }

                    root.tasks.register("compileBlockPrintModelBakerProbe", JavaCompile) {
                        source = root.sourceSets.blockprintBaker.java
                        classpath = root.sourceSets.blockprintBaker.compileClasspath
                        destinationDirectory = root.layout.buildDirectory.dir("blockprint-model-baker/classes")
                        options.encoding = "UTF-8"
                        options.release = 21
                    }

                    root.tasks.register("runBlockPrintModelBakerProbe", JavaExec) {
                        dependsOn("compileBlockPrintModelBakerProbe")
                        group = "model extraction"
                        description = "Runs BlockPrint's standalone headless model-baker classpath probe."
                        mainClass = "io.github.moxisuki.blockprint.tools.CreateModelBakerProbe"
                        classpath = root.files(
                            root.layout.buildDirectory.dir("blockprint-model-baker/classes"),
                            root.sourceSets.blockprintBaker.runtimeClasspath
                        )
                        jvmArgs("-Djava.awt.headless=true")
                        systemProperty("blockprint.baker.outputDir", new File(root.buildDir, "blockprint-model-baker/generated").absolutePath)
                    }
                }
            }
            """.trimIndent(),
        )
        logger.lifecycle("Wrote Create model-baker init script to ${initFile.absolutePath}")
    }
}

tasks.register<Exec>("runCreateModelBakerProbe") {
    group = "model extraction"
    description = "Runs the standalone tool classpath probe inside the local Create Gradle project."
    dependsOn("writeCreateModelBakerInitScript")

    val createDir = file(createSourceDirProvider.get())
    val gradlew = createDir.resolve(if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "gradlew")

    doFirst {
        require(createDir.isDirectory) {
            "Create source directory not found: ${createDir.absolutePath}. Pass -PcreateSourceDir=/path/to/Create."
        }
        require(gradlew.isFile) {
            "Create Gradle wrapper not found: ${gradlew.absolutePath}"
        }
    }

    workingDir = createDir
    commandLine(
        gradlew.absolutePath,
        "-I", initScript.get().asFile.absolutePath,
        "runBlockPrintModelBakerProbe",
        "--no-daemon",
        "-Dblockprint.baker.srcDir=${toolSourceDir.asFile.absolutePath}",
        "-Dblockprint.baker.resourcesDir=${toolResourcesDir.asFile.absolutePath}",
    )
}

tasks.register<Exec>("runCreateModelBakerDataProbe") {
    group = "model extraction"
    description = "Runs the standalone tool as a headless NeoForge data-run mod inside the local Create Gradle project."
    dependsOn("writeCreateModelBakerInitScript")

    val createDir = file(createSourceDirProvider.get())
    val gradlew = createDir.resolve(if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "gradlew")

    doFirst {
        require(createDir.isDirectory) {
            "Create source directory not found: ${createDir.absolutePath}. Pass -PcreateSourceDir=/path/to/Create."
        }
        require(gradlew.isFile) {
            "Create Gradle wrapper not found: ${gradlew.absolutePath}"
        }
    }

    workingDir = createDir
    commandLine(
        gradlew.absolutePath,
        "-I", initScript.get().asFile.absolutePath,
        "runBlockprintBakerData",
        "--no-daemon",
        "-Dblockprint.baker.srcDir=${toolSourceDir.asFile.absolutePath}",
        "-Dblockprint.baker.resourcesDir=${toolResourcesDir.asFile.absolutePath}",
    )
}

tasks.register<Exec>("runCreateModelBakerClientProbe") {
    group = "model extraction"
    description = "Runs the standalone tool in a real NeoForge client lifecycle and exits after model baking."
    dependsOn("writeCreateModelBakerInitScript")

    val createDir = file(createSourceDirProvider.get())
    val gradlew = createDir.resolve(if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "gradlew")

    doFirst {
        require(createDir.isDirectory) {
            "Create source directory not found: ${createDir.absolutePath}. Pass -PcreateSourceDir=/path/to/Create."
        }
        require(gradlew.isFile) {
            "Create Gradle wrapper not found: ${gradlew.absolutePath}"
        }
    }

    workingDir = createDir
    commandLine(
        gradlew.absolutePath,
        "-I", initScript.get().asFile.absolutePath,
        "runBlockprintBakerClient",
        "--no-daemon",
        "-Dblockprint.baker.srcDir=${toolSourceDir.asFile.absolutePath}",
        "-Dblockprint.baker.resourcesDir=${toolResourcesDir.asFile.absolutePath}",
    )
}

tasks.register<Exec>("runCreateModelBakerClientExport") {
    group = "model extraction"
    description = "Exports baked static model manifests from a real NeoForge client lifecycle. Pass -Dblockprint.baker.namespaces=create or -Dblockprint.baker.blocks=id1,id2."
    dependsOn("writeCreateModelBakerInitScript")

    val createDir = file(createSourceDirProvider.get())
    val gradlew = createDir.resolve(if (System.getProperty("os.name").lowercase().contains("windows")) "gradlew.bat" else "gradlew")

    doFirst {
        require(createDir.isDirectory) {
            "Create source directory not found: ${createDir.absolutePath}. Pass -PcreateSourceDir=/path/to/Create."
        }
        require(gradlew.isFile) {
            "Create Gradle wrapper not found: ${gradlew.absolutePath}"
        }
    }

    val requestedBlocks = System.getProperty("blockprint.baker.blocks")
    val requestedNamespaces = System.getProperty("blockprint.baker.namespaces")
    val requestedModIds = System.getProperty("blockprint.baker.modids")
    val requestedBlockstates = System.getProperty("blockprint.baker.blockstates")
    val requestedBlockstateFile = System.getProperty("blockprint.baker.blockstateFile")
    val requestedManifestName = System.getProperty("blockprint.baker.manifestName")
    val writeCombinedManifest = System.getProperty("blockprint.baker.writeCombinedManifest")
    val writeProbe = System.getProperty("blockprint.baker.writeProbe")
    val recorderPropertyNames = listOf(
        "blockprint.baker.captureBlockDispatcher",
        "blockprint.baker.captureBlockEntityRenderers",
        "blockprint.baker.captureFlywheelVisuals",
        "blockprint.baker.deferUntilClientTick",
        "blockprint.baker.loadDefaultServerConfigs",
        "blockprint.baker.atlasSpriteUvEpsilon",
        "blockprint.baker.rendererFallbackTexture",
    )
    val hasExplicitSelection = listOf(
        requestedBlocks,
        requestedNamespaces,
        requestedModIds,
        requestedBlockstates,
        requestedBlockstateFile,
    ).any { !it.isNullOrBlank() }
    val forwardedSelection = buildList {
        if (!requestedBlocks.isNullOrBlank()) add("-Dblockprint.baker.blocks=$requestedBlocks")
        if (!requestedNamespaces.isNullOrBlank()) add("-Dblockprint.baker.namespaces=$requestedNamespaces")
        if (!requestedModIds.isNullOrBlank()) add("-Dblockprint.baker.modids=$requestedModIds")
        if (!requestedBlockstates.isNullOrBlank()) add("-Dblockprint.baker.blockstates=$requestedBlockstates")
        if (!requestedBlockstateFile.isNullOrBlank()) add("-Dblockprint.baker.blockstateFile=$requestedBlockstateFile")
        if (!requestedManifestName.isNullOrBlank()) add("-Dblockprint.baker.manifestName=$requestedManifestName")
        if (!writeCombinedManifest.isNullOrBlank()) add("-Dblockprint.baker.writeCombinedManifest=$writeCombinedManifest")
        if (!writeProbe.isNullOrBlank()) add("-Dblockprint.baker.writeProbe=$writeProbe")
        for (propertyName in recorderPropertyNames) {
            val propertyValue = System.getProperty(propertyName)
            if (!propertyValue.isNullOrBlank()) add("-D$propertyName=$propertyValue")
        }
        if (!hasExplicitSelection) add("-Dblockprint.baker.namespaces=create")
    }

    workingDir = createDir
    commandLine(
        listOf(
            gradlew.absolutePath,
            "-I", initScript.get().asFile.absolutePath,
            "runBlockprintBakerClient",
            "--no-daemon",
            "-Dblockprint.baker.srcDir=${toolSourceDir.asFile.absolutePath}",
            "-Dblockprint.baker.resourcesDir=${toolResourcesDir.asFile.absolutePath}",
        ) + forwardedSelection,
    )
}
