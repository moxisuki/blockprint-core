import java.io.File

data class ExtractedCreateAssembly(
    val block: String,
    val blockEntity: String,
    val renderer: String?,
    val visual: String?,
    val partSources: Map<String, Set<String>>,
)

// ── Create static model assembly extractor ────────────────────────
//
// Build-time helper for researching / generating static model assemblies from
// Create's source tree. It intentionally has no runtime dependency and does
// not run Minecraft or NeoForge; it scans stable source anchors:
//   - AllPartialModels.java: partial constant -> model path
//   - AllBlockEntityTypes.java: block id -> renderer / visual class
//   - renderer / visual sources: referenced AllPartialModels constants
//
// Usage:
//   ./gradlew extractCreateStaticAssemblies `
//     -PcreateSourceDir=C:/path/to/Create
//
// Default source dir matches the local research cache used by Codex.
val createSourceDirProvider = providers.gradleProperty("createSourceDir")
    .orElse(providers.environmentVariable("CREATE_SOURCE_DIR"))
    .orElse(
        providers.provider {
            File(System.getProperty("user.home"), ".codex/repo-cache/model-adapter-research/Create")
                .absolutePath
        },
    )

tasks.register("extractCreateStaticAssemblies") {
    group = "model extraction"
    description = "Extracts static Create partial-model assemblies into a JSON manifest."

    val outputFile = layout.buildDirectory.file("generated/create-static-assemblies/create-static-assemblies.json")
    inputs.property("createSourceDir", createSourceDirProvider)
    outputs.file(outputFile)

    doLast {
        val createDir = file(createSourceDirProvider.get())
        require(createDir.isDirectory) {
            "Create source directory not found: ${createDir.absolutePath}. " +
                "Pass -PcreateSourceDir=/path/to/Create or set CREATE_SOURCE_DIR."
        }

        fun sourceFile(relativePath: String): File =
            createDir.resolve(relativePath.replace('/', File.separatorChar))

        fun readSource(relativePath: String): String {
            val file = sourceFile(relativePath)
            require(file.isFile) { "Required Create source file not found: ${file.absolutePath}" }
            return file.readText()
        }

        fun jsonEscape(value: String): String = buildString(value.length + 8) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }

        fun quoted(value: String): String = "\"" + jsonEscape(value) + "\""

        fun blockIdFromConstant(constant: String): String =
            "create:" + constant.lowercase()

        val sourceRoot = sourceFile("src/main/java")
        val javaSourcesByClass = sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .associateBy { it.nameWithoutExtension }

        val partialsSource = readSource("src/main/java/com/simibubi/create/AllPartialModels.java")
        val partials = Regex("""\b([A-Z0-9_]+)\s*=\s*(block|entity)\("([^"]+)"\)""")
            .findAll(partialsSource)
            .associate { match ->
                val name = match.groupValues[1]
                val folder = match.groupValues[2]
                val path = match.groupValues[3]
                name to "create:$folder/$path"
            }
            .toSortedMap()

        val blockEntityTypes = readSource("src/main/java/com/simibubi/create/AllBlockEntityTypes.java")
        val entryRegex = Regex(
            """blockEntity\("([^"]+)".*?validBlocks\((.*?)\).*?\.register\(\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val allPartialRef = Regex("""AllPartialModels\.([A-Z][A-Z0-9_]*)(?![A-Za-z0-9_])""")
        val allBlockRef = Regex("""AllBlocks\.([A-Z0-9_]+)""")
        val rendererRef = Regex("""\.renderer\(\(\)\s*->\s*([A-Za-z0-9_]+)::new\)""")
        val visualCall = Regex("""\.visual\(\(\)\s*->\s*([^\r\n]+)""")
        val classNameRef = Regex("""([A-Za-z0-9_]+)(?:::new|\.)""")

        val assemblies = mutableListOf<ExtractedCreateAssembly>()

        for (entry in entryRegex.findAll(blockEntityTypes)) {
            val blockEntityId = entry.groupValues[1]
            val entryText = entry.value
            val validBlocksText = entry.groupValues[2]
            val blocks = allBlockRef.findAll(validBlocksText)
                .map { blockIdFromConstant(it.groupValues[1]) }
                .distinct()
                .toList()
            if (blocks.isEmpty()) continue

            val renderer = rendererRef.find(entryText)?.groupValues?.get(1)
            val rawVisual = visualCall.find(entryText)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.substringBefore(',')
                ?.trim()
                ?.let { if ("::" in it) it.removeSuffix(")") else it }

            val partSources = linkedMapOf<String, MutableSet<String>>()
            fun addPart(partial: String, source: String) {
                partSources.getOrPut(partial) { linkedSetOf() }.add(source)
            }

            for (partial in allPartialRef.findAll(entryText)) {
                addPart(partial.groupValues[1], "blockEntityRegistration")
            }

            val sourceClasses = linkedSetOf<String>()
            renderer?.let { sourceClasses.add(it) }
            rawVisual?.let { visual ->
                if ("::new" in visual) {
                    classNameRef.find(visual)?.groupValues?.get(1)?.let { sourceClasses.add(it) }
                }
            }

            for (sourceClass in sourceClasses) {
                val file = javaSourcesByClass[sourceClass] ?: continue
                val text = file.readText()
                for (partial in allPartialRef.findAll(text)) {
                    addPart(partial.groupValues[1], sourceClass)
                }
            }
            renderer?.let { rendererClass ->
                val rendererText = javaSourcesByClass[rendererClass]?.readText().orEmpty()
                if ("shaft(getRotationAxisOf" in rendererText) {
                    addPart("KINETIC_SHAFT", "$rendererClass.getRenderedBlockState")
                }
            }

            if (partSources.isEmpty()) continue

            for (block in blocks) {
                assemblies.add(
                    ExtractedCreateAssembly(
                        block = block,
                        blockEntity = "create:$blockEntityId",
                        renderer = renderer,
                        visual = rawVisual,
                        partSources = partSources,
                    ),
                )
            }
        }

        val sortedAssemblies = assemblies.sortedWith(
            compareBy<ExtractedCreateAssembly> { it.block }.thenBy { it.blockEntity },
        )
        fun modelForPartial(partial: String): String? =
            if (partial == "KINETIC_SHAFT") "create:block/shaft" else partials[partial]

        fun transformForPartial(assembly: ExtractedCreateAssembly, partial: String, sources: Set<String>): String {
            if (partial == "KINETIC_SHAFT") return "kinetic_shaft_axis"
            val visual = assembly.visual.orEmpty()
            if ("OrientedRotatingVisual.of(AllPartialModels.$partial)" in visual) return "facing_from_south"
            if ("OrientedRotatingVisual.backHorizontal(AllPartialModels.$partial)" in visual) return "facing_from_south"
            val rendererText = assembly.renderer
                ?.let { javaSourcesByClass[it] }
                ?.readText()
                .orEmpty()
            if ("partialFacing(AllPartialModels.$partial" in rendererText) return "facing_from_south"
            if (sources.any { it.endsWith("Visual") } && "SingleAxisRotatingVisual" in visual) return "kinetic_axis"
            return "world"
        }

        val out = buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"sourceDir\": ${quoted(createDir.absolutePath.replace('\\', '/'))},")
            appendLine("  \"generator\": \"extractCreateStaticAssemblies\",")
            appendLine("  \"partials\": {")
            partials.entries.forEachIndexed { index, (name, model) ->
                append("    ${quoted(name)}: ${quoted(model)}")
                if (index != partials.size - 1) append(',')
                appendLine()
            }
            appendLine("  },")
            appendLine("  \"assemblies\": [")
            sortedAssemblies.forEachIndexed { assemblyIndex, assembly ->
                appendLine("    {")
                appendLine("      \"block\": ${quoted(assembly.block)},")
                appendLine("      \"blockEntity\": ${quoted(assembly.blockEntity)},")
                appendLine("      \"base\": \"blockstate\",")
                appendLine("      \"renderer\": ${assembly.renderer?.let(::quoted) ?: "null"},")
                appendLine("      \"visual\": ${assembly.visual?.let(::quoted) ?: "null"},")
                appendLine("      \"parts\": [")
                assembly.partSources.entries.sortedBy { it.key }.forEachIndexed { partIndex, (partial, sources) ->
                    appendLine("        {")
                    appendLine("          \"partial\": ${quoted(partial)},")
                    appendLine("          \"model\": ${modelForPartial(partial)?.let(::quoted) ?: "null"},")
                    appendLine("          \"transform\": ${quoted(transformForPartial(assembly, partial, sources))},")
                    appendLine("          \"sources\": [${sources.sorted().joinToString(", ") { quoted(it) }}]")
                    append("        }")
                    if (partIndex != assembly.partSources.size - 1) append(',')
                    appendLine()
                }
                appendLine("      ]")
                append("    }")
                if (assemblyIndex != sortedAssemblies.size - 1) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }

        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(out)
        logger.lifecycle(
            "Extracted ${sortedAssemblies.size} Create static assemblies and ${partials.size} partial models to ${file.absolutePath}",
        )
    }
}
