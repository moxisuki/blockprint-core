# BlockPrint 1.0 — Blueprint Reader Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename `Litematic*` → `BlockPrint*` uniformly, restructure packages by responsibility, optimize cold parsing throughput, add `peek()` header-only read, and prepare the 1.0.0 release.

**Architecture:** Public API surface (`api/`) delegates to per-format readers (`format/`) over a shared NBT layer (`nbt/`). Pure data models live in `model/`. Cross-layer utilities in `internal/`. Errors surface as a single public `BlockPrintException` with internal `NbtFormatException` / `GlbExportException` as `cause`.

**Tech Stack:** Kotlin 2.2.10 Multiplatform, Gradle 9.5.1, JDK 21, no third-party deps (kotlin.test only). GLB layer unchanged in behavior; renamed + re-packaged only.

**Reference Spec:** `docs/superpowers/specs/2026-07-02-blockprint-reader-rewrite-design.md`

---

## File Map

### Files to create (final state, all at `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/` unless noted)

**`api/`** (4):
- `api/BlockPrintReader.kt`
- `api/BlockPrintConverter.kt`
- `api/BlockPrintToGlb.kt`
- `api/GlbExportOptions.kt` (moved from `glb/`)

**`model/`** (9):
- `model/BlockPrintDocument.kt`
- `model/BlockPrintRegion.kt`
- `model/BlockPrintSummary.kt`
- `model/BlockPalette.kt`
- `model/BlockState.kt`
- `model/Position.kt`
- `model/MaterialList.kt`
- `model/MinecraftVersions.kt`
- `model/SchematicFormat.kt`

**`nbt/`** (5):
- `nbt/NbtReader.kt`
- `nbt/NbtWriter.kt`
- `nbt/NbtDocument.kt`
- `nbt/NbtTag.kt`
- `nbt/NbtTagType.kt`

**`format/`** (5 dir + 9 files):
- `format/FormatDetector.kt`
- `format/litematica/LitematicaReader.kt`
- `format/litematica/LitematicaWriter.kt`
- `format/sponge/SpongeReader.kt`
- `format/sponge/SpongeWriter.kt`
- `format/structure/StructureReader.kt`
- `format/structure/StructureWriter.kt`
- `format/buildinghelper/BuildingHelperReader.kt`
- `format/buildinghelper/BuildingHelperWriter.kt`

**`exceptions/`** (3):
- `exceptions/BlockPrintException.kt`
- `exceptions/NbtFormatException.kt`
- `exceptions/GlbExportException.kt`

**`internal/`** (4):
- `internal/BlockStatePacker.kt`
- `internal/VarInt.kt`
- `internal/PackedBlocks.kt`
- `internal/NbtAccessors.kt`

**`glb/`** restructured into 7 subdirs: `glb/writer/`, `glb/mesh/`, `glb/model/`, `glb/texture/`, `glb/platform/`, `glb/internal/`, `glb/synthetic/` (file count unchanged, only package paths and a few `Litematic*` type references change).

### Files to delete

- `LitematicReader.kt`, `Litematic.kt`, `LitematicRegion.kt`, `LitematicException.kt`, `BlueprintConverter.kt`, `LitematicToGlb.kt` (after forwarders prove unnecessary — Phase 7)
- `internal/LitematicParser.kt`, `internal/BuildingHelperParser.kt`, `internal/format/LitematicWriter.kt`, `internal/format/SpongeWriter.kt`, `internal/format/StructureWriter.kt`, `internal/format/BuildingHelperWriter.kt`
- `glb/LitematicToGlb.kt` (moved to `api/BlockPrintToGlb.kt`)
- `glb/GlbExportOptions.kt` (moved to `api/GlbExportOptions.kt`)

### New test files (at `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/`)

- `api/BlockPrintReaderTest.kt` (renamed from existing)
- `api/BlockPrintConverterTest.kt` (renamed)
- `api/BlockPrintReaderPeekTest.kt` (new)
- `api/BlockPrintReaderPeekNoAllocationTest.kt` (new)
- `api/BlockPrintReaderReadStreamTest.kt` (new)
- `api/BlockPrintReaderErrorMessagesTest.kt` (new)
- `api/BlockPrintExceptionUnwrappingTest.kt` (new)
- `nbt/NbtWriterTest.kt` (path only)
- `nbt/NbtReaderStreamReadTest.kt` (new)
- `nbt/NbtReaderSkipSubtreeTest.kt` (new)
- `format/FormatDetectorTest.kt` (renamed + extended)
- `format/litematica/LitematicaReaderTest.kt` (renamed + new peek cases)
- `format/litematica/LitematicaWriterTest.kt` (path only)
- `format/sponge/SpongeReaderTest.kt` (renamed + new peek cases)
- `format/sponge/SpongeWriterTest.kt` (path only)
- `format/structure/StructureReaderTest.kt` (renamed + new peek cases)
- `format/structure/StructureWriterTest.kt` (path only)
- `format/buildinghelper/BuildingHelperReaderTest.kt` (renamed + new peek cases)
- `format/buildinghelper/BuildingHelperWriterTest.kt` (path only)
- `internal/BlockStatePackerTest.kt` (path only)
- `internal/PackedBlocksUnpackParityTest.kt` (new)
- `internal/VarIntCodecParityTest.kt` (new)
- `benchmark/BlockPrintParsingBenchmark.kt` (new, non-CI)

### Renamed/moved test files in `glb/`

All 20+ existing GLB test files: package path updates only (test bodies unchanged).

---

## Phase 0: Pre-flight (no code change)

### Task 0.1: Confirm branch and baseline tests

**Files:** none modified

- [ ] **Step 1: Confirm working branch**

Run: `git rev-parse --abbrev-ref HEAD`
Expected: `rewrite/blueprint-reader`

- [ ] **Step 2: Confirm baseline tests pass**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL` (existing 0.2.2 test suite passes)

- [ ] **Step 3: Commit no-op**

```bash
git commit --allow-empty -m "chore(plan): confirm baseline before rewrite"
```

---

## Phase 1: Foundation — new packages with typealias forwarders

**Goal:** Create the new package structure with empty shells, then add typealiases that forward to old `Litematic*` classes so existing tests pass through the new path. This validates the migration path before any rename.

### Task 1.1: Create empty `model/` package shells

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/SchematicFormat.kt`
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockPalette.kt`
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockState.kt`
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/Position.kt`
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/MaterialList.kt`
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/MinecraftVersions.kt`

- [ ] **Step 1: Create `model/SchematicFormat.kt`**

```kotlin
@file:Suppress("PackageDirectoryMismatch")
package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.SchematicFormat
typealias SchematicFormat = io.github.moxisuki.blockprint.core.SchematicFormat
```

- [ ] **Step 2: Create the other 5 shells with single-line typealiases**

For each of `BlockPalette`, `BlockState`, `Position`, `MaterialList`, `MinecraftVersions` create the file with the same pattern:

```kotlin
@file:Suppress("PackageDirectoryMismatch")
package io.github.moxisuki.blockprint.core.model
import io.github.moxisuki.blockprint.core.BlockPalette   // adjust to actual class name
typealias BlockPalette = io.github.moxisuki.blockprint.core.BlockPalette
```

- [ ] **Step 3: Build to confirm typealiases resolve**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/
git commit -m "refactor(model): add typealias shells in new model/ package"
```

### Task 1.2: Create `api/` shell with forwarder

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReader.kt`

- [ ] **Step 1: Create forwarder**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import java.io.File
import java.io.InputStream

/**
 * Temporary forwarder. Will be promoted to the new read API in Phase 2/3
 * with [BlockPrintDocument] / [BlockPrintRegion] return types.
 */
object BlockPrintReader {
    @JvmStatic
    fun read(file: File): Litematic = LitematicReader.read(file)
    @JvmStatic
    fun read(input: InputStream): Litematic = LitematicReader.read(input)
    @JvmStatic
    fun read(bytes: ByteArray): Litematic = LitematicReader.read(bytes)
    @JvmStatic
    fun readLenient(file: File): Litematic = LitematicReader.readLenient(file)
    @JvmStatic
    fun readLenient(input: InputStream): Litematic = LitematicReader.readLenient(input)
    @JvmStatic
    fun readLenient(bytes: ByteArray): Litematic = LitematicReader.readLenient(bytes)
    @JvmStatic
    fun detectFormat(file: File): SchematicFormat = LitematicReader.detectFormat(file)
    @JvmStatic
    fun detectFormat(input: InputStream): SchematicFormat = LitematicReader.detectFormat(input)
    @JvmStatic
    fun detectFormat(bytes: ByteArray): SchematicFormat = LitematicReader.detectFormat(bytes)
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL` (existing tests still hit old LitematicReader, new one is unused)

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/
git commit -m "refactor(api): add BlockPrintReader forwarder (delegates to LitematicReader)"
```

### Task 1.3: Add `BlockPrintDocument` data class + forwarder

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintDocument.kt`

- [ ] **Step 1: Create the data class with conversion from Litematic**

```kotlin
package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.Litematic as LegacyLitematic
import io.github.moxisuki.blockprint.core.LitematicRegion as LegacyRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat

/**
 * New canonical document model. In Phase 2 readers will produce this
 * directly; in Phase 1 it wraps the legacy [LegacyLitematic] for
 * interop with existing readers.
 *
 * - [minecraftDataVersion] mirrors NBT field `MinecraftDataVersion`.
 * - [version] is the schematic file format version.
 * - [regions] preserves NBT insertion order.
 */
data class BlockPrintDocument(
    val minecraftDataVersion: Int?,
    val version: Int?,
    val name: String,
    val author: String,
    val description: String,
    val regions: List<BlockPrintRegion>,
    val format: SchematicFormat = SchematicFormat.Unknown,
) {
    val primaryRegion: BlockPrintRegion? get() = regions.firstOrNull()

    fun blockCount(includeAir: Boolean = false): Int {
        var total = 0
        for (region in regions) {
            if (includeAir) {
                total += region.width * region.height * region.depth
            } else {
                region.rawBlocks.forEach { if (it != 0) total++ }
            }
        }
        return total
    }

    companion object {
        /** Adapter from the legacy model. Used only during migration. */
        fun fromLegacy(lit: LegacyLitematic): BlockPrintDocument = BlockPrintDocument(
            minecraftDataVersion = lit.minecraftDataVersion,
            version = lit.version,
            name = lit.name,
            author = lit.author,
            description = lit.description,
            regions = lit.regions.map { BlockPrintRegion.fromLegacy(it) },
            format = lit.format,
        )
    }
}
```

- [ ] **Step 2: Create `BlockPrintRegion` companion file**

```kotlin
package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.LitematicRegion as LegacyRegion
import io.github.moxisuki.blockprint.core.Position

/**
 * Single region. Stores the decoded block array as a flat [IntArray]
 * in y-major / z-middle / x-minor order. [rawBlocks] should not be mutated.
 */
class BlockPrintRegion(
    val name: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val position: Position,
    val palette: BlockPalette,
    blocks: IntArray? = null,
) {
    private val blocks: IntArray = blocks ?: IntArray(
        (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
    val rawBlocks: IntArray get() = blocks

    fun rawIndex(x: Int, y: Int, z: Int): Int {
        checkBounds(x, y, z)
        return y * (width * depth) + z * width + x
    }

    fun getBlock(x: Int, y: Int, z: Int): Int {
        checkBounds(x, y, z)
        return blocks[rawIndex(x, y, z)]
    }

    fun setBlock(x: Int, y: Int, z: Int, paletteIndex: Int) {
        checkBounds(x, y, z)
        require(paletteIndex in palette.indices) {
            "Palette index $paletteIndex out of range for palette of size ${palette.size}"
        }
        blocks[rawIndex(x, y, z)] = paletteIndex
    }

    fun blockAt(x: Int, y: Int, z: Int) = palette[getBlock(x, y, z)]
    fun isAir(x: Int, y: Int, z: Int): Boolean = getBlock(x, y, z) == 0
    fun toBlockArray(): IntArray = blocks.copyOf()

    private fun checkBounds(x: Int, y: Int, z: Int) {
        require(x in 0 until width && y in 0 until height && z in 0 until depth) {
            "($x, $y, $z) out of bounds for region $width x $height x $depth"
        }
    }

    companion object {
        fun fromLegacy(r: LegacyRegion): BlockPrintRegion = BlockPrintRegion(
            name = r.name,
            width = r.width,
            height = r.height,
            depth = r.depth,
            position = r.position,
            palette = r.palette,
            blocks = r.toBlockArray(),
        )
    }
}
```

- [ ] **Step 3: Build + run tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL` (new types exist, no consumer yet)

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintDocument.kt \
        src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintRegion.kt
git commit -m "feat(model): add BlockPrintDocument and BlockPrintRegion with legacy adapter"
```

### Task 1.4: Smoke test the new model adapter

**Files:**
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintDocumentAdapterTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.LitematicReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlockPrintDocumentAdapterTest {

    @Test
    fun `fromLegacy preserves all fields`() {
        val legacy = LitematicReader.read(javaClass.getResourceAsStream("/test/litematic/single-region.litematic")!!)
        val doc = BlockPrintDocument.fromLegacy(legacy)

        assertEquals(legacy.minecraftDataVersion, doc.minecraftDataVersion)
        assertEquals(legacy.version, doc.version)
        assertEquals(legacy.name, doc.name)
        assertEquals(legacy.author, doc.author)
        assertEquals(legacy.description, doc.description)
        assertEquals(legacy.format, doc.format)
        assertEquals(legacy.regions.size, doc.regions.size)
        for ((a, b) in legacy.regions.zip(doc.regions)) {
            assertEquals(a.name, b.name)
            assertEquals(a.width, b.width)
            assertEquals(a.height, b.height)
            assertEquals(a.depth, b.depth)
            assertEquals(a.position, b.position)
            assertEquals(a.palette, b.palette)
            assertContentEquals(a.toBlockArray(), b.toBlockArray())
        }
    }

    @Test
    fun `blockCount matches legacy`() {
        val legacy = LitematicReader.read(javaClass.getResourceAsStream("/test/litematic/single-region.litematic")!!)
        val doc = BlockPrintDocument.fromLegacy(legacy)
        assertEquals(legacy.blockCount(includeAir = false), doc.blockCount(includeAir = false))
        assertEquals(legacy.blockCount(includeAir = true), doc.blockCount(includeAir = true))
    }

    @Test
    fun `primaryRegion delegates`() {
        val legacy = LitematicReader.read(javaClass.getResourceAsStream("/test/litematic/single-region.litematic")!!)
        val doc = BlockPrintDocument.fromLegacy(legacy)
        assertNotNull(doc.primaryRegion)
        assertEquals(legacy.primaryRegion!!.name, doc.primaryRegion!!.name)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew jvmTest --tests "*BlockPrintDocumentAdapterTest*" --console=plain`
Expected: `BUILD SUCCESSFUL` with 3 tests passing

- [ ] **Step 3: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintDocumentAdapterTest.kt
git commit -m "test(model): cover BlockPrintDocument.fromLegacy adapter"
```

---

## Phase 2: Move NBT layer to `nbt/` package

**Goal:** Move NbtReader/NbtWriter/NbtTag/etc. to `nbt/` package. Pure move — no behavior change. Update all imports.

### Task 2.1: Create `nbt/` shells with typealiases

**Files:**
- Create: 5 files in `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/nbt/`

- [ ] **Step 1: Create the 5 typealias shells**

For each of `NbtReader`, `NbtWriter`, `NbtDocument`, `NbtTag`, `NbtTagType`, create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/nbt/<Name>.kt`:

```kotlin
@file:Suppress("PackageDirectoryMismatch")
package io.github.moxisuki.blockprint.core.nbt
import io.github.moxisuki.blockprint.core.NbtReader
typealias NbtReader = io.github.moxisuki.blockprint.core.NbtReader
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/nbt/
git commit -m "refactor(nbt): add nbt/ typealias shells"
```

### Task 2.2: Migrate test file paths

**Files:**
- Move: `src/jvmTest/.../NbtWriterTest.kt` → `src/jvmTest/.../nbt/NbtWriterTest.kt`

- [ ] **Step 1: Move file and update package declaration**

```bash
mkdir -p src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/nbt/
git mv src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/NbtWriterTest.kt \
       src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/nbt/NbtWriterTest.kt
```

Then edit the file's `package` line to:
```kotlin
package io.github.moxisuki.blockprint.core.nbt
```

- [ ] **Step 2: Run the moved test**

Run: `./gradlew jvmTest --tests "*nbt.NbtWriterTest*" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(nbt): move NbtWriterTest to nbt/ package"
```

---

## Phase 3: Split `LitematicParser` into per-format readers

**Goal:** Decompose the 648-line `LitematicParser` into 4 focused readers in `format/<name>/`. Build a shared `FormatDetector`. Add `readHeader(root)` to each reader for Peek support.

### Task 3.1: Create `internal/NbtAccessors.kt` with shared helpers

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/NbtAccessors.kt`

- [ ] **Step 1: Create shared accessors**

```kotlin
package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.exceptions.NbtFormatException

/**
 * Shared helpers for reading common NBT patterns. Used by all format
 * readers. Imported from the old LitematicParser during migration.
 */
internal object NbtAccessors {
    fun readInt3(compound: NbtTag.CompoundTag, label: String): IntArray {
        val x = (compound.get("x") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("$label missing int field 'x'")
        val y = (compound.get("y") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("$label missing int field 'y'")
        val z = (compound.get("z") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("$label missing int field 'z'")
        return intArrayOf(x, y, z)
    }

    fun readStringOrEmpty(c: NbtTag.CompoundTag?, key: String): String {
        val t = c?.get(key) as? NbtTag.StringTag ?: return ""
        return t.value
    }

    fun readIntOrNull(c: NbtTag.CompoundTag, key: String): Int? =
        (c.get(key) as? NbtTag.IntTag)?.value

    /**
     * Read a Sponge v3 palette key of the form `minecraft:foo[k=v,k2=v2]`
     * (or just `minecraft:foo` when no properties).
     */
    fun parseSpongeV3Key(key: String): io.github.moxisuki.blockprint.core.BlockState {
        val bracket = key.indexOf('[')
        if (bracket < 0) return io.github.moxisuki.blockprint.core.BlockState(key, null)
        val name = key.substring(0, bracket)
        val body = key.substring(bracket + 1, key.length - 1)
        if (body.isEmpty()) return io.github.moxisuki.blockprint.core.BlockState(name, null)
        val props = LinkedHashMap<String, String>()
        for (pair in body.split(',')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val pk = pair.substring(0, eq).trim()
            var pv = pair.substring(eq + 1).trim()
            if (pv.length >= 2 && pv.first() == '"' && pv.last() == '"') {
                pv = pv.substring(1, pv.length - 1)
            }
            if (pk.isNotEmpty()) props[pk] = pv
        }
        return io.github.moxisuki.blockprint.core.BlockState(name, props)
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/NbtAccessors.kt
git commit -m "feat(internal): extract shared NBT accessors"
```

### Task 3.2: Create `exceptions/BlockPrintException.kt`, `NbtFormatException.kt`, `GlbExportException.kt`

**Files:**
- Create: 3 files in `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/exceptions/`

- [ ] **Step 1: Create `BlockPrintException.kt` (temporarily alias) and add new ones**

```kotlin
// exceptions/BlockPrintException.kt
@file:Suppress("PackageDirectoryMismatch")
package io.github.moxisuki.blockprint.core.exceptions
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
typealias BlockPrintException = LitematicException
```

```kotlin
// exceptions/NbtFormatException.kt
package io.github.moxisuki.blockprint.core.exceptions

/**
 * Thrown by the NBT layer when bytes are malformed. The reader API
 * catches this and re-throws as [BlockPrintException] with the
 * underlying cause preserved.
 */
class NbtFormatException(
    val offset: Long,
    message: String,
) : RuntimeException("NBT error at offset 0x${offset.toString(16)}: $message")
```

```kotlin
// exceptions/GlbExportException.kt
package io.github.moxisuki.blockprint.core.exceptions

/**
 * Thrown by the GLB layer on export failures. The reader API
 * catches this and re-throws as [BlockPrintException] with the
 * underlying cause preserved.
 */
class GlbExportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [ ] **Step 2: Build + tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/exceptions/
git commit -m "feat(exceptions): add BlockPrintException alias, NbtFormatException, GlbExportException"
```

### Task 3.3: Create `format/FormatDetector.kt`

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/FormatDetector.kt`

- [ ] **Step 1: Create the detector**

```kotlin
package io.github.moxisuki.blockprint.core.format

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.SchematicFormat

/**
 * Content-sniffing detector for NBT schematic roots. Order matches
 * real-world frequency: Litematica first (most common), then
 * WorldEdit/Sponge v3, vanilla Structure, Sponge v2, PartialNbt,
 * Unknown.
 */
internal object FormatDetector {
    fun detect(root: NbtTag.CompoundTag): SchematicFormat = when {
        root.contains("Regions") -> SchematicFormat.Litematica
        (root.get("Schematic") as? NbtTag.CompoundTag)
            ?.let { (it.get("Version") as? NbtTag.IntTag)?.value == 3 && it.contains("Blocks") } == true -> SchematicFormat.Sponge
        (root.get("palette") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound &&
            ((root.get("blocks") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound
                || (root.get("Blocks") as? NbtTag.ListTag)?.elementType == NbtTagType.Compound) -> SchematicFormat.Structure
        (root.get("Metadata") as? NbtTag.CompoundTag)
            ?.contains("EnclosingSize") == true -> SchematicFormat.Sponge
        root.contains("Size") || root.contains("size") -> SchematicFormat.PartialNbt
        else -> SchematicFormat.Unknown
    }
}
```

- [ ] **Step 2: Add unit test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/format/FormatDetectorTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.format

import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.SchematicFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDetectorTest {
    @Test
    fun `Litematica with Regions compound`() {
        val root = NbtTag.CompoundTag(listOf("Regions" to NbtTag.CompoundTag(emptyList())))
        assertEquals(SchematicFormat.Litematica, FormatDetector.detect(root))
    }

    @Test
    fun `Sponge v3 with Schematic wrapper`() {
        val inner = NbtTag.CompoundTag(listOf(
            "Version" to NbtTag.IntTag(3),
            "Blocks" to NbtTag.CompoundTag(emptyList()),
        ))
        val root = NbtTag.CompoundTag(listOf("Schematic" to inner))
        assertEquals(SchematicFormat.Sponge, FormatDetector.detect(root))
    }

    @Test
    fun `Structure with palette+blocks lists`() {
        val root = NbtTag.CompoundTag(listOf(
            "palette" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
            "blocks" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
        ))
        assertEquals(SchematicFormat.Structure, FormatDetector.detect(root))
    }

    @Test
    fun `Sponge v2 with Metadata-EnclosingSize`() {
        val root = NbtTag.CompoundTag(listOf(
            "Metadata" to NbtTag.CompoundTag(listOf(
                "EnclosingSize" to NbtTag.CompoundTag(emptyList()),
            )),
        ))
        assertEquals(SchematicFormat.Sponge, FormatDetector.detect(root))
    }

    @Test
    fun `PartialNbt with Size compound`() {
        val root = NbtTag.CompoundTag(listOf("Size" to NbtTag.CompoundTag(emptyList())))
        assertEquals(SchematicFormat.PartialNbt, FormatDetector.detect(root))
    }

    @Test
    fun `Unknown for empty root`() {
        val root = NbtTag.CompoundTag(emptyList())
        assertEquals(SchematicFormat.Unknown, FormatDetector.detect(root))
    }

    @Test
    fun `Litematica with Sponge-compat metadata still detected as Litematica`() {
        val root = NbtTag.CompoundTag(listOf(
            "Regions" to NbtTag.CompoundTag(emptyList()),
            "Metadata" to NbtTag.CompoundTag(listOf("EnclosingSize" to NbtTag.CompoundTag(emptyList()))),
        ))
        assertEquals(SchematicFormat.Litematica, FormatDetector.detect(root))
    }
}
```

- [ ] **Step 3: Run test**

Run: `./gradlew jvmTest --tests "*FormatDetectorTest*" --console=plain`
Expected: 7 tests pass

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/FormatDetector.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/format/FormatDetectorTest.kt
git commit -m "feat(format): add FormatDetector with content-based dispatch"
```

### Task 3.4: Create `format/litematica/LitematicaReader.kt`

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/litematica/LitematicaReader.kt`

- [ ] **Step 1: Lift the Litematica branch from LitematicParser**

The new file's `parse(root)` body is the same code currently in `LitematicParser.parse(root)` lines 47-94, with these changes:
- Package: `io.github.moxisuki.blockprint.core.format.litematica`
- Returns `BlockPrintDocument` (not `Litematic`)
- Uses `BlockPrintRegion` (not `LitematicRegion`)
- Calls `LitematicaReader.readHeader(root)` for Peek (new method, shown below)
- Uses `NbtAccessors.readInt3` / `readStringOrEmpty` / `readIntOrNull` instead of private helpers
- Uses `FormatDetector.detect(root)` for the isSponge decision

```kotlin
package io.github.moxisuki.blockprint.core.format.litematica

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.internal.BlockStatePacker
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion

/**
 * Parse a Litematica-format schematic root into [BlockPrintDocument].
 *
 * Also detects Litematica-with-Sponge-compat files (root has both
 * `Regions` and `Metadata/EnclosingSize`): these remain Litematica.
 */
internal object LitematicaReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument {
        val regionsTag = root.get("Regions")
            ?: throw BlockPrintException("Litematic root is missing 'Regions' compound")
        if (regionsTag !is NbtTag.CompoundTag) {
            throw BlockPrintException("'Regions' must be a compound, got ${regionsTag::class.simpleName}")
        }
        val metadata = (root.get("Metadata") as? NbtTag.CompoundTag)
        val isSponge = metadata?.contains("EnclosingSize") == true
        val regions = regionsTag.entries().map { (regionName, regionTag) ->
            parseRegion(
                name = regionName,
                region = regionTag as? NbtTag.CompoundTag
                    ?: throw BlockPrintException("Region '$regionName' must be a compound"),
                isSponge = isSponge,
                metadata = metadata,
            )
        }
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            name = metadata?.let { NbtAccessors.readStringOrEmpty(it, "Name") }
                ?.ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") }
                ?: NbtAccessors.readStringOrEmpty(root, "Name"),
            author = metadata?.let { NbtAccessors.readStringOrEmpty(it, "Author") }
                ?.ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") }
                ?: NbtAccessors.readStringOrEmpty(root, "Author"),
            description = metadata?.let { NbtAccessors.readStringOrEmpty(it, "Description") }
                ?.ifEmpty { NbtAccessors.readStringOrEmpty(root, "Description") }
                ?: NbtAccessors.readStringOrEmpty(root, "Description"),
            regions = regions,
            format = if (isSponge) SchematicFormat.Sponge else SchematicFormat.Litematica,
        )
    }

    /** Peek: read only root metadata, skip Regions subtree entirely. */
    fun readHeader(root: NbtTag.CompoundTag): io.github.moxisuki.blockprint.core.model.BlockPrintSummary =
        io.github.moxisuki.blockprint.core.model.BlockPrintSummary(
            format = SchematicFormat.Litematica,
            name = NbtAccessors.readStringOrEmpty(root, "Name"),
            author = NbtAccessors.readStringOrEmpty(root, "Author"),
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
        )

    private fun parseRegion(
        name: String, region: NbtTag.CompoundTag, isSponge: Boolean, metadata: NbtTag.CompoundTag?,
    ): BlockPrintRegion {
        val (rawWidth, rawHeight, rawDepth) = if (isSponge) {
            val enclosing = metadata?.get("EnclosingSize") as? NbtTag.CompoundTag
                ?: throw BlockPrintException("Sponge schematic: Metadata/EnclosingSize missing")
            NbtAccessors.readInt3(enclosing, "EnclosingSize")
        } else {
            val sizeTag = region.require("Size") as? NbtTag.CompoundTag
                ?: throw BlockPrintException("Region '$name' missing Size compound")
            NbtAccessors.readInt3(sizeTag, "Size")
        }
        val width = kotlin.math.abs(rawWidth)
        val height = kotlin.math.abs(rawHeight)
        val depth = kotlin.math.abs(rawDepth)
        require(width > 0 && height > 0 && depth > 0) {
            "Region '$name' has invalid dimension: ${width}x${height}x${depth}"
        }

        val position = region.get("Position")?.let {
            if (it is NbtTag.CompoundTag) {
                val (x, y, z) = NbtAccessors.readInt3(it, "Position")
                Position(x, y, z)
            } else null
        } ?: Position.ZERO

        val paletteTag = region.require("BlockStatePalette") as? NbtTag.ListTag
            ?: throw BlockPrintException("Region '$name' missing BlockStatePalette list")
        require(paletteTag.elementType == NbtTagType.Compound) {
            "Region '$name' BlockStatePalette element type must be COMPOUND, got ${paletteTag.elementType}"
        }
        val palette = BlockPalette(paletteTag.value.map { parseBlockState(it as NbtTag.CompoundTag) })

        val blockStatesTag = region.require("BlockStates") as? NbtTag.LongArrayTag
            ?: throw BlockPrintException("Region '$name' missing BlockStates long array")
        val nbits = palette.bitsPerBlock
        BlockStatePacker.validateLength(blockStatesTag.value, nbits, width, height, depth)
        val blocks = BlockStatePacker.unpack(blockStatesTag.value, nbits, width, height, depth)

        return BlockPrintRegion(name, width, height, depth, position, palette, blocks)
    }

    private fun parseBlockState(tag: NbtTag.CompoundTag): BlockState {
        val name = tag.get("Name") as? NbtTag.StringTag
            ?: throw BlockPrintException("Block state missing 'Name' string")
        val props = tag.get("Properties") as? NbtTag.CompoundTag
        val properties: Map<String, String>? = if (props == null) null else {
            if (props.value.isEmpty()) emptyMap() else props.value.associate { (k, v) ->
                val str = v as? NbtTag.StringTag
                    ?: throw BlockPrintException("Block state property '$k' must be a string")
                k to str.value
            }
        }
        return BlockState(name.value, properties)
    }
}
```

- [ ] **Step 2: Build (will fail — BlockPrintSummary not yet defined)**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: FAIL with "Unresolved reference: BlockPrintSummary"

Proceed to Task 3.5 immediately to add `BlockPrintSummary`; then re-run.

- [ ] **Step 3: Commit (after Task 3.5)**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/litematica/
git commit -m "feat(format-litematica): extract LitematicaReader from LitematicParser"
```

### Task 3.5: Create `model/BlockPrintSummary.kt`

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintSummary.kt`

- [ ] **Step 1: Create the data class**

```kotlin
package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.SchematicFormat

/**
 * Lightweight header-only view of a schematic file. Returned by
 * [io.github.moxisuki.blockprint.core.api.BlockPrintReader.peek].
 *
 * Contains only file-level metadata; the block data is not parsed.
 */
data class BlockPrintSummary(
    val format: SchematicFormat,
    val name: String,
    val author: String,
    val description: String,
    val version: Int?,
    val minecraftDataVersion: Int?,
)
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/model/BlockPrintSummary.kt
git commit -m "feat(model): add BlockPrintSummary for peek header-only read"
```

### Task 3.6: Create Sponge/Structure/BuildingHelper readers

**Files:** (create each as a new file)

- `format/sponge/SpongeReader.kt`
- `format/structure/StructureReader.kt`
- `format/buildinghelper/BuildingHelperReader.kt`

- [ ] **Step 1: Create `format/sponge/SpongeReader.kt`**

Lift `LitematicParser.parseSponge` (v2) and `parseSpongeV3` (v3) into a single object with two static functions. Returns `BlockPrintDocument`. Includes `readHeader(root)` for Peek. Uses `NbtAccessors.parseSpongeV3Key` for v3 palette keys. Uses `internal/VarInt.kt` (created in Task 5.1).

```kotlin
package io.github.moxisuki.blockprint.core.format.sponge

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.internal.VarInt
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary

internal object SpongeReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument {
        val inner = root.get("Schematic") as? NbtTag.CompoundTag
        if (inner != null && (inner.get("Version") as? NbtTag.IntTag)?.value == 3 && inner.contains("Blocks")) {
            return parseV3(inner)
        }
        return parseV2(root)
    }

    fun readHeader(root: NbtTag.CompoundTag): BlockPrintSummary {
        val inner = root.get("Schematic") as? NbtTag.CompoundTag
        return if (inner != null && (inner.get("Version") as? NbtTag.IntTag)?.value == 3) {
            val meta = inner.get("Metadata") as? NbtTag.CompoundTag
            val worldEdit = meta?.get("WorldEdit") as? NbtTag.CompoundTag
            BlockPrintSummary(
                format = SchematicFormat.Sponge,
                name = NbtAccessors.readStringOrEmpty(worldEdit, "Name")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(meta, "Name") }
                    .ifEmpty { NbtAccessors.readStringOrEmpty(inner, "Name") },
                author = NbtAccessors.readStringOrEmpty(worldEdit, "Author")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(meta, "Author") }
                    .ifEmpty { NbtAccessors.readStringOrEmpty(inner, "Author") },
                description = NbtAccessors.readStringOrEmpty(meta, "Description")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(inner, "Description") },
                version = NbtAccessors.readIntOrNull(inner, "Version"),
                minecraftDataVersion = NbtAccessors.readIntOrNull(inner, "DataVersion"),
            )
        } else {
            val meta = root.get("Metadata") as? NbtTag.CompoundTag
            BlockPrintSummary(
                format = SchematicFormat.Sponge,
                name = NbtAccessors.readStringOrEmpty(meta, "Name")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
                author = NbtAccessors.readStringOrEmpty(meta, "Author")
                    .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
                description = NbtAccessors.readStringOrEmpty(root, "Description"),
                version = NbtAccessors.readIntOrNull(root, "Version"),
                minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
            )
        }
    }

    private fun parseV2(root: NbtTag.CompoundTag): BlockPrintDocument {
        val width = (root.get("Width") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("Sponge: missing int 'Width'")
        val height = (root.get("Height") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("Sponge: missing int 'Height'")
        val depth = (root.get("Length") as? NbtTag.IntTag)?.value
            ?: throw BlockPrintException("Sponge: missing int 'Length'")
        require(width > 0 && height > 0 && depth > 0) { "Sponge: invalid dimension ${width}x${height}x${depth}" }

        val paletteTag = root.get("Palette") as? NbtTag.CompoundTag
            ?: throw BlockPrintException("Sponge: 'Palette' must be a compound")
        val paletteEntries = mutableListOf<Pair<Int, BlockState>>()
        for ((k, v) in paletteTag.entries()) {
            val idx = k.toIntOrNull() ?: throw BlockPrintException("Sponge: palette key '$k' is not an int string")
            paletteEntries += idx to parseBlockState(v as NbtTag.CompoundTag)
        }
        paletteEntries.sortBy { it.first }
        val palette = BlockPalette(paletteEntries.map { it.second })

        val blockData = (root.get("BlockData") as? NbtTag.ByteArrayTag)
            ?: throw BlockPrintException("Sponge: missing BlockData byte array")
        val total = width * height * depth
        val flat = IntArray(total)
        val src = blockData.value
        var pos = 0
        var k = 0
        for (y in 0 until height) for (z in 0 until depth) for (x in 0 until width) {
            if (k >= total) throw BlockPrintException("Sponge: BlockData underrun (need $total varints, got fewer)")
            val v = VarInt.decode(src, pos)
            pos = v.nextPos
            flat[y * (width * depth) + z * width + x] = v.value
            k++
        }
        if (k < total) throw BlockPrintException("Sponge: BlockData has trailing bytes (decoded $k of $total)")

        val position = (root.get("Offset") as? NbtTag.CompoundTag)?.let {
            val (x, y, z) = NbtAccessors.readInt3(it, "Offset")
            Position(x, y, z)
        } ?: Position.ZERO

        val meta = root.get("Metadata") as? NbtTag.CompoundTag
        val region = BlockPrintRegion("Sponge", width, height, depth, position, palette, flat)
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
            version = NbtAccessors.readIntOrNull(root, "Version"),
            name = NbtAccessors.readStringOrEmpty(meta, "Name").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(meta, "Author").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(meta, "Description")
                .ifEmpty { NbtAccessors.readStringOrEmpty(root, "Description") },
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    private fun parseV3(inner: NbtTag.CompoundTag): BlockPrintDocument {
        val width = (inner.get("Width") as? NbtTag.ShortTag)?.value?.toInt()
            ?: throw BlockPrintException("Sponge v3: missing short 'Width'")
        val height = (inner.get("Height") as? NbtTag.ShortTag)?.value?.toInt()
            ?: throw BlockPrintException("Sponge v3: missing short 'Height'")
        val depth = (inner.get("Length") as? NbtTag.ShortTag)?.value?.toInt()
            ?: throw BlockPrintException("Sponge v3: missing short 'Length'")
        require(width > 0 && height > 0 && depth > 0) { "Sponge v3: invalid dimension ${width}x${height}x${depth}" }

        val blocksCompound = inner.get("Blocks") as? NbtTag.CompoundTag
            ?: throw BlockPrintException("Sponge v3: 'Blocks' must be a compound")
        val paletteTag = blocksCompound.get("Palette") as? NbtTag.CompoundTag
            ?: throw BlockPrintException("Sponge v3: Blocks/Palette must be a compound")
        val nameToId = mutableMapOf<String, Int>()
        for ((key, v) in paletteTag.entries()) {
            val id = (v as? NbtTag.IntTag)?.value
                ?: throw BlockPrintException("Sponge v3: Palette value for '$key' must be IntTag")
            nameToId[key] = id
        }
        val palette = BlockPalette(nameToId.entries.sortedBy { it.value }.map { NbtAccessors.parseSpongeV3Key(it.key) })

        val blockData = (blocksCompound.get("Data") as? NbtTag.ByteArrayTag)
            ?: throw BlockPrintException("Sponge v3: Blocks/Data missing or not a byte array")
        val total = width * height * depth
        val flat = IntArray(total)
        val src = blockData.value
        var pos = 0
        var k = 0
        for (y in 0 until height) for (z in 0 until depth) for (x in 0 until width) {
            if (k >= total) throw BlockPrintException("Sponge v3: BlockData underrun (need $total varints, got fewer)")
            val v = VarInt.decode(src, pos)
            pos = v.nextPos
            flat[y * (width * depth) + z * width + x] = v.value
            k++
        }
        if (k < total) throw BlockPrintException("Sponge v3: BlockData has trailing bytes (decoded $k of $total)")

        val position = (inner.get("Offset") as? NbtTag.IntArrayTag)?.let { arr ->
            require(arr.value.size >= 3) { "Sponge v3: Offset must have at least 3 ints, got ${arr.value.size}" }
            Position(arr.value[0], arr.value[1], arr.value[2])
        } ?: Position.ZERO

        val region = BlockPrintRegion("Sponge", width, height, depth, position, palette, flat)
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(inner, "DataVersion"),
            version = NbtAccessors.readIntOrNull(inner, "Version"),
            name = NbtAccessors.readStringOrEmpty(root = inner, key = "Name"),
            author = NbtAccessors.readStringOrEmpty(root = inner, key = "Author"),
            description = NbtAccessors.readStringOrEmpty(root = inner, key = "Description"),
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    private fun parseBlockState(tag: NbtTag.CompoundTag): BlockState {
        val name = tag.get("Name") as? NbtTag.StringTag
            ?: throw BlockPrintException("Block state missing 'Name' string")
        val props = tag.get("Properties") as? NbtTag.CompoundTag
        val properties: Map<String, String>? = if (props == null) null else {
            if (props.value.isEmpty()) emptyMap() else props.value.associate { (k, v) ->
                val str = v as? NbtTag.StringTag
                    ?: throw BlockPrintException("Block state property '$k' must be a string")
                k to str.value
            }
        }
        return BlockState(name.value, properties)
    }
}
```

- [ ] **Step 2: Create `format/structure/StructureReader.kt`**

Lift the Litematica `parseStructure` body into `StructureReader.parse(root)`. Returns `BlockPrintDocument`. Includes `readHeader(root)`.

```kotlin
package io.github.moxisuki.blockprint.core.format.structure

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.internal.NbtAccessors
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary

internal object StructureReader {
    fun parse(root: NbtTag.CompoundTag): BlockPrintDocument {
        val (width, height, depth) = readSizeLenient(root)
        val rawPalette = (root.get("palette") as? NbtTag.ListTag)
            ?: throw BlockPrintException("Structure file: 'palette' must be a ListTag")
        require(rawPalette.elementType == NbtTagType.Compound) {
            "Structure palette element type must be Compound, got ${rawPalette.elementType}"
        }
        val rawEntries = rawPalette.value.map { parseBlockState(it as NbtTag.CompoundTag) }
        val palette = BlockPalette(listOf(BlockState("minecraft:air", null)) + rawEntries)

        val blocksList = (root.get("blocks") as? NbtTag.ListTag) ?: (root.get("Blocks") as? NbtTag.ListTag)
            ?: throw BlockPrintException("Structure file: 'blocks' must be a ListTag")
        require(blocksList.elementType == NbtTagType.Compound) {
            "Structure blocks element type must be Compound, got ${blocksList.elementType}"
        }

        val dense = IntArray(width * height * depth)
        for (element in blocksList.value) {
            val entry = element as NbtTag.CompoundTag
            val posList = entry.get("pos") as? NbtTag.ListTag
                ?: throw BlockPrintException("Structure block entry missing 'pos' list")
            require(posList.value.size == 3) { "Structure block pos must have 3 elements, got ${posList.value.size}" }
            val x = (posList.value[0] as NbtTag.IntTag).value
            val y = (posList.value[1] as NbtTag.IntTag).value
            val z = (posList.value[2] as NbtTag.IntTag).value
            val state = (entry.get("state") as? NbtTag.IntTag)?.value
                ?: throw BlockPrintException("Structure block entry missing 'state'")
            val idx = y * (width * depth) + z * width + x
            require(idx in dense.indices) { "Structure block pos [$x,$y,$z] out of bounds ${width}x${height}x${depth}" }
            dense[idx] = state + 1
        }

        val region = BlockPrintRegion("Structure", width, height, depth, Position.ZERO, palette, dense)
        return BlockPrintDocument(
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
            version = null,
            name = NbtAccessors.readStringOrEmpty(root, "name").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(root, "author").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            regions = listOf(region),
            format = SchematicFormat.Structure,
        )
    }

    fun readHeader(root: NbtTag.CompoundTag): BlockPrintSummary {
        val (w, h, d) = readSizeLenient(root)
        return BlockPrintSummary(
            format = SchematicFormat.Structure,
            name = NbtAccessors.readStringOrEmpty(root, "name").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Name") },
            author = NbtAccessors.readStringOrEmpty(root, "author").ifEmpty { NbtAccessors.readStringOrEmpty(root, "Author") },
            description = NbtAccessors.readStringOrEmpty(root, "Description"),
            version = null,
            minecraftDataVersion = NbtAccessors.readIntOrNull(root, "DataVersion"),
        )
    }

    private fun readSizeLenient(root: NbtTag.CompoundTag): IntArray {
        (root.get("Size") as? NbtTag.CompoundTag)?.let { return NbtAccessors.readInt3(it, "Size") }
        (root.get("size") as? NbtTag.ListTag)?.let { sizeList ->
            require(sizeList.elementType == NbtTagType.Int && sizeList.value.size == 3) {
                "Root 'size' list must be 3 ints, got ${sizeList.elementType} of ${sizeList.value.size}"
            }
            val ints = sizeList.value.map { (it as NbtTag.IntTag).value }
            return intArrayOf(ints[0], ints[1], ints[2])
        }
        throw BlockPrintException("Structure file: missing 'size'")
    }

    private fun parseBlockState(tag: NbtTag.CompoundTag): BlockState {
        val name = tag.get("Name") as? NbtTag.StringTag
            ?: throw BlockPrintException("Block state missing 'Name' string")
        val props = tag.get("Properties") as? NbtTag.CompoundTag
        val properties: Map<String, String>? = if (props == null) null else {
            if (props.value.isEmpty()) emptyMap() else props.value.associate { (k, v) ->
                val str = v as? NbtTag.StringTag
                    ?: throw BlockPrintException("Block state property '$k' must be a string")
                k to str.value
            }
        }
        return BlockState(name.value, properties)
    }
}
```

- [ ] **Step 3: Create `format/buildinghelper/BuildingHelperReader.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.format.buildinghelper

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintRegion
import io.github.moxisuki.blockprint.core.model.BlockPalette
import io.github.moxisuki.blockprint.core.Position

/**
 * Parser for BuildingHelper ("建筑小帮手") JSON blueprints. The
 * existing logic in internal/BuildingHelperParser is lifted here
 * unchanged except for the BlockPrint* return types.
 *
 * The original parser is regex-based and reads the full file. Peek
 * uses a substring slice to avoid touching statePosArrayList.
 */
internal object BuildingHelperReader {
    fun parse(bytes: ByteArray): BlockPrintDocument {
        // Existing BuildingHelperParser.parse logic, adapted to return BlockPrintDocument
        // (See src/commonMain/kotlin/.../internal/BuildingHelperParser.kt for the regex/parsing rules.)
        // The full body is preserved as-is with the wrapping types swapped.
        TODO("Lift implementation from BuildingHelperParser in Task 3.6 step 4")
    }

    fun readHeader(bytes: ByteArray): BlockPrintSummary {
        val text = String(bytes, Charsets.UTF_8)
        val nameMatch = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)
        return BlockPrintSummary(
            format = SchematicFormat.BuildingHelper,
            name = nameMatch?.groupValues?.get(1)?.unescape() ?: "",
            author = "",
            description = "",
            version = null,
            minecraftDataVersion = null,
        )
    }

    private fun String.unescape(): String = this.replace("\\\"", "\"").replace("\\\\", "\\")
}
```

> **Note:** the full `parse(bytes)` body must be migrated from `BuildingHelperParser.kt` byte-for-byte. The task executor reads that file and adapts the return type from `Litematic` → `BlockPrintDocument` and the region type analogously. The body itself is unchanged logic.

- [ ] **Step 4: Build + run tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/
git commit -m "feat(format): extract per-format readers from LitematicParser"
```

### Task 3.7: Add `VarInt` (sits between 3.4 and 3.6) — pulled forward

> **Implementation note:** Task 3.6 step 1 already references `VarInt.decode(...)`. Create `internal/VarInt.kt` here as a stand-alone helper (this is Phase 5 work but is needed to compile SpongeReader).

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/VarInt.kt`

- [ ] **Step 1: Create VarInt utility**

```kotlin
package io.github.moxisuki.blockprint.core.internal

import io.github.moxisuki.blockprint.core.exceptions.NbtFormatException

/**
 * Protobuf-style varint (7 bits/byte, MSB = continuation).
 * Used by Sponge v2 + v3 schematic block data.
 */
internal object VarInt {
    data class Result(val value: Int, val nextPos: Int)

    fun decode(src: ByteArray, startPos: Int): Result {
        var result = 0
        var shift = 0
        var pos = startPos
        while (true) {
            if (pos >= src.size) throw NbtFormatException(pos.toLong(), "Sponge: varint truncated at byte $pos")
            val b = src[pos].toInt() and 0xFF
            pos++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return Result(result, pos)
            shift += 7
            if (shift >= 35) throw NbtFormatException(pos.toLong(), "Sponge: varint too long (overflow)")
        }
    }

    fun encode(out: java.io.ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv().toInt() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/VarInt.kt
git commit -m "feat(internal): add shared VarInt codec for Sponge"
```

### Task 3.8: Migrate writers to `format/<name>/` packages

**Files:**
- Move: 4 writer files from `internal/format/` to `format/<name>/`
- Update: their package declarations and `Litematic`/`LitematicRegion` → `BlockPrintDocument`/`BlockPrintRegion`

- [ ] **Step 1: Move `LitematicWriter.kt` and rename**

```bash
mkdir -p src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/litematica/
git mv src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriter.kt \
       src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/litematica/LitematicaWriter.kt
```

Then edit:
- `package` line: `io.github.moxisuki.blockprint.core.format.litematica`
- Replace `Litematic` → `BlockPrintDocument` and `LitematicRegion` → `BlockPrintRegion` throughout
- Add `import io.github.moxisuki.blockprint.core.model.BlockPrintDocument`
- Add `import io.github.moxisuki.blockprint.core.model.BlockPrintRegion`

- [ ] **Step 2: Move the other 3 writers**

Same procedure for:
- `SpongeWriter.kt` → `format/sponge/SpongeWriter.kt` (Litematic → BlockPrintDocument; LitematicRegion → BlockPrintRegion)
- `StructureWriter.kt` → `format/structure/StructureWriter.kt`
- `BuildingHelperWriter.kt` → `format/buildinghelper/BuildingHelperWriter.kt`

- [ ] **Step 3: Move 4 corresponding test files**

Same destinations under `src/jvmTest/.../format/<name>/`:
- `LitematicWriterTest.kt`, `SpongeWriterTest.kt`, `StructureWriterTest.kt`, `BuildingHelperWriterTest.kt`

- [ ] **Step 4: Build + run tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(format): move writers from internal/format/ to format/<name>/"
```

---

## Phase 4: Promote `BlockPrintReader` to real API

**Goal:** Replace the forwarder in `api/BlockPrintReader.kt` with a real implementation that uses the new per-format readers and `BlockPrintDocument`.

### Task 4.1: Real `api/BlockPrintReader.kt`

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReader.kt`

- [ ] **Step 1: Replace the file with the real implementation**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.NbtReader
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import io.github.moxisuki.blockprint.core.exceptions.NbtFormatException
import io.github.moxisuki.blockprint.core.format.FormatDetector
import io.github.moxisuki.blockprint.core.format.buildinghelper.BuildingHelperReader
import io.github.moxisuki.blockprint.core.format.litematica.LitematicaReader
import io.github.moxisuki.blockprint.core.format.sponge.SpongeReader
import io.github.moxisuki.blockprint.core.format.structure.StructureReader
import io.github.moxisuki.blockprint.core.internal.LitematicParser
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import io.github.moxisuki.blockprint.core.model.BlockPrintSummary
import java.io.File
import java.io.InputStream

/**
 * Public entry point for parsing blueprint files.
 *
 * Detects format by content (not extension), supports gzip-wrapped
 * NBT, and accepts the legacy Litematica `.litematic`, WorldEdit
 * Sponge v2/v3 (`.schematic`/`.schem`), vanilla Structure (`.nbt`),
 * and BuildingHelper JSON (`.json`).
 */
object BlockPrintReader {
    @JvmStatic
    fun read(file: File): BlockPrintDocument = file.inputStream().use { read(it) }

    @JvmStatic
    fun read(input: InputStream): BlockPrintDocument = input.use { stream ->
        val root = NbtReader.readRoot(stream)
        parseRoot(root)
    }

    @JvmStatic
    fun read(bytes: ByteArray): BlockPrintDocument {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return try {
                BuildingHelperReader.parse(bytes)
            } catch (e: Exception) {
                throw BlockPrintException("建筑小帮手解析失败: ${e.message}", e)
            }
        }
        val root = NbtReader.readRoot(bytes)
        return parseRoot(root)
    }

    @JvmStatic
    fun readLenient(file: File): BlockPrintDocument = file.inputStream().use { readLenient(it) }
    @JvmStatic
    fun readLenient(input: InputStream): BlockPrintDocument = input.use { stream ->
        readLenient(stream.readBytes())
    }
    @JvmStatic
    fun readLenient(bytes: ByteArray): BlockPrintDocument {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return try { BuildingHelperReader.parse(bytes) }
            catch (e: Exception) { throw BlockPrintException("建筑小帮手解析失败: ${e.message}", e) }
        }
        val root = NbtReader.readRoot(bytes)
        return LitematicParser.parseLenient(root)
    }

    @JvmStatic
    @JvmOverloads
    fun detectFormat(file: File): SchematicFormat = file.inputStream().use { detectFormat(it) }
    @JvmStatic
    fun detectFormat(input: InputStream): SchematicFormat = input.use { stream ->
        detectFormat(stream.readBytes())
    }
    @JvmStatic
    fun detectFormat(bytes: ByteArray): SchematicFormat {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return SchematicFormat.BuildingHelper
        val root = try { NbtReader.readRoot(bytes) } catch (e: Exception) { return SchematicFormat.Unknown }
        return FormatDetector.detect(root)
    }

    // --- Peek ---
    @JvmStatic
    fun peek(file: File): BlockPrintSummary = file.inputStream().use { peek(it) }
    @JvmStatic
    fun peek(input: InputStream): BlockPrintSummary = input.use { stream ->
        peek(stream.readBytes())
    }
    @JvmStatic
    fun peek(bytes: ByteArray): BlockPrintSummary {
        if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return BuildingHelperReader.readHeader(bytes)
        val root = NbtReader.readRoot(bytes)
        return when (FormatDetector.detect(root)) {
            SchematicFormat.Litematica -> LitematicaReader.readHeader(root)
            SchematicFormat.Sponge -> SpongeReader.readHeader(root)
            SchematicFormat.Structure -> StructureReader.readHeader(root)
            SchematicFormat.PartialNbt -> BlockPrintSummary(
                format = SchematicFormat.PartialNbt, name = "", author = "", description = "",
                version = null,
                minecraftDataVersion = io.github.moxisuki.blockprint.core.internal.NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
            )
            SchematicFormat.Unknown -> BlockPrintSummary(SchematicFormat.Unknown, "", "", "", null, null)
            SchematicFormat.BuildingHelper -> error("BuildingHelper routed by byte sniff above")
        }
    }

    // --- internals ---
    private fun parseRoot(root: NbtTag.CompoundTag): BlockPrintDocument = try {
        when (FormatDetector.detect(root)) {
            SchematicFormat.Litematica -> LitematicaReader.parse(root)
            SchematicFormat.Sponge -> SpongeReader.parse(root)
            SchematicFormat.Structure -> StructureReader.parse(root)
            SchematicFormat.PartialNbt -> LitematicParser.parseLenient(root)
            SchematicFormat.Unknown, SchematicFormat.BuildingHelper -> throw BlockPrintException("Not a recognized schematic format")
        }
    } catch (e: NbtFormatException) {
        throw BlockPrintException("NBT parse failed at offset 0x${e.offset.toString(16)}", e)
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run all tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`. Old tests still call `LitematicReader` (which still exists) — they pass. New `BlockPrintReader` tests (added in Task 4.2) verify the new path.

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReader.kt
git commit -m "feat(api): promote BlockPrintReader to real implementation with peek()"
```

### Task 4.2: Add `BlockPrintReaderTest.kt` and Peek tests

**Files:**
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReaderTest.kt`
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReaderPeekTest.kt`
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReaderErrorMessagesTest.kt`

- [ ] **Step 1: Create `BlockPrintReaderTest.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BlockPrintReaderTest {
    private val fixture = "src/jvmTest/resources/test/litematic/single-region.litematic"

    @Test fun `read from file returns BlockPrintDocument`() {
        val doc: BlockPrintDocument = BlockPrintReader.read(File(fixture))
        assertNotNull(doc.primaryRegion)
    }

    @Test fun `read from InputStream matches read from file`() {
        val fromFile = BlockPrintReader.read(File(fixture))
        val fromStream = File(fixture).inputStream().use { BlockPrintReader.read(it) }
        assertEquals(fromFile.name, fromStream.name)
        assertEquals(fromFile.regions.size, fromStream.regions.size)
    }

    @Test fun `read from bytes matches read from file`() {
        val fromFile = BlockPrintReader.read(File(fixture))
        val fromBytes = BlockPrintReader.read(File(fixture).readBytes())
        assertEquals(fromFile.regions.size, fromBytes.regions.size)
    }

    @Test fun `detectFormat from bytes returns Litematica`() {
        val bytes = File(fixture).readBytes()
        assertEquals(SchematicFormat.Litematica, BlockPrintReader.detectFormat(bytes))
    }

    @Test fun `readLenient on partial file still returns document`() {
        val fromFile = BlockPrintReader.readLenient(File(fixture))
        assertNotNull(fromFile)
    }
}
```

- [ ] **Step 2: Create `BlockPrintReaderPeekTest.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.SchematicFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

class BlockPrintReaderPeekTest {
    private val litematicFixture = "src/jvmTest/resources/test/litematic/single-region.litematic"
    private val structureFixture = "src/jvmTest/resources/test/structure/example.nbt"
    private val bhFixture = "src/jvmTest/resources/test/buildinghelper/example.json"

    @Test fun `peek Litematica from file matches read metadata`() {
        val peeked = BlockPrintReader.peek(File(litematicFixture))
        val read = BlockPrintReader.read(File(litematicFixture))
        assertEquals(SchematicFormat.Litematica, peeked.format)
        assertEquals(read.name, peeked.name)
        assertEquals(read.author, peeked.author)
        assertEquals(read.minecraftDataVersion, peeked.minecraftDataVersion)
    }

    @Test fun `peek Litematica from InputStream matches file peek`() {
        val fromFile = BlockPrintReader.peek(File(litematicFixture))
        val fromStream = File(litematicFixture).inputStream().use { BlockPrintReader.peek(it) }
        assertEquals(fromFile.format, fromStream.format)
        assertEquals(fromFile.name, fromStream.name)
    }

    @Test fun `peek Litematica from bytes matches file peek`() {
        val fromFile = BlockPrintReader.peek(File(litematicFixture))
        val fromBytes = BlockPrintReader.peek(File(litematicFixture).readBytes())
        assertEquals(fromFile.name, fromBytes.name)
    }

    @Test fun `peek Structure from bytes`() {
        val peeked = BlockPrintReader.peek(File(structureFixture))
        assertEquals(SchematicFormat.Structure, peeked.format)
    }

    @Test fun `peek BuildingHelper from bytes detects format`() {
        val peeked = BlockPrintReader.peek(File(bhFixture))
        assertEquals(SchematicFormat.BuildingHelper, peeked.format)
    }

    @Test fun `peek non-schematic bytes throws BlockPrintException`() {
        val garbage = "this is not an nbt file".toByteArray()
        try {
            BlockPrintReader.peek(garbage)
            fail("Expected BlockPrintException")
        } catch (e: io.github.moxisuki.blockprint.core.exceptions.BlockPrintException) {
            assertNotNull(e)
        }
    }

    @Test fun `peek empty bytes throws`() {
        try {
            BlockPrintReader.peek(ByteArray(0))
            fail("Expected BlockPrintException")
        } catch (e: io.github.moxisuki.blockprint.core.exceptions.BlockPrintException) {
            assertNotNull(e)
        }
    }
}
```

- [ ] **Step 3: Create `BlockPrintReaderErrorMessagesTest.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.exceptions.BlockPrintException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class BlockPrintReaderErrorMessagesTest {
    @Test fun `garbage bytes throw with descriptive message`() {
        val e = runCatching { BlockPrintReader.read("junk".toByteArray()) }.exceptionOrNull()
        assertTrue(e is BlockPrintException, "expected BlockPrintException, got $e")
        assertContains(e!!.message!!, "NBT")
    }

    @Test fun `empty bytes throw with EOF-ish message`() {
        val e = runCatching { BlockPrintReader.read(ByteArray(0)) }.exceptionOrNull()
        assertTrue(e is BlockPrintException)
    }

    @Test fun `cause preserved for NbtFormatException`() {
        val e = runCatching { BlockPrintReader.read(byteArrayOf(0x05)) }.exceptionOrNull()
        assertTrue(e is BlockPrintException)
    }
}
```

- [ ] **Step 4: Run new tests**

Run: `./gradlew jvmTest --tests "*api.*" --console=plain`
Expected: `BUILD SUCCESSFUL` (all new tests pass; adjust fixture paths if your test resources differ)

- [ ] **Step 5: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/api/
git commit -m "test(api): cover BlockPrintReader, Peek, and error messages"
```

---

## Phase 5: Performance optimizations

**Goal:** Implement the perf optimizations from spec §3. Establish a baseline first; revert if not meeting 1.4× / 1.6× thresholds.

### Task 5.1: Add `BlockPrintParsingBenchmark.kt` (baseline)

**Files:**
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/benchmark/BlockPrintParsingBenchmark.kt`

- [ ] **Step 1: Create the benchmark file**

```kotlin
package io.github.moxisuki.blockprint.core.benchmark

import io.github.moxisuki.blockprint.core.LitematicReader
import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/**
 * Hand-rolled wall-clock benchmark. CI skips via env var. Run with:
 *   ./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*"
 *
 * NOT in CI: `CI=true ./gradlew jvmTest` is a no-op for this class.
 */
class BlockPrintParsingBenchmark {
    private val fixture5MB = "src/jvmTest/resources/test/benchmark/5mb.litematic"
    private val fixture50MB = "src/jvmTest/resources/test/benchmark/50mb.litematic"
    private val fixture200MB = "src/jvmTest/resources/test/benchmark/200mb.litematic"

    @Test fun `parse 50MB Litematica baseline`() {
        assumeFalse("CI".equals(System.getenv("CI"), ignoreCase = true))
        val file = File(fixture50MB)
        assumeTrue(file.exists(), "fixture missing: $fixture50MB")

        // Warmup
        repeat(3) { LitematicReader.read(file) }

        val samples = LongArray(5)
        repeat(5) { i ->
            samples[i] = measureNanoTime { LitematicReader.read(file) }
        }
        val median = samples.sorted()[2]
        println("[BENCHMARK] Litematica 50MB: ${median / 1_000_000} ms (median of 5)")
        // No assertion: this is a measurement, not a test.
    }
}
```

- [ ] **Step 2: Generate fixtures (separate shell step, not Gradle)**

```bash
# Use existing litematic files in test/create/assets/create/lang/ if 5/50/200MB exist.
# If not, generate using the library itself by writing random litematic-shaped bytes.
```

- [ ] **Step 3: Run baseline, record median**

Run: `./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*" -i --console=plain | tee /tmp/bench-before.txt`

- [ ] **Step 4: Commit**

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/benchmark/
git commit -m "test(bench): add wall-clock baseline for Litematica parsing"
```

### Task 5.2: Add `PackedBlocks` (specialized unpacking)

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/PackedBlocks.kt`
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/PackedBlocksUnpackParityTest.kt`

- [ ] **Step 1: Create `PackedBlocks.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.internal

/**
 * Specialized paths for [BlockStatePacker.unpack]. The general path
 * shifts and masks per cell; this version handles the common Litematica
 * widths (4, 8) with one Long read producing 8/4 cells.
 */
internal object PackedBlocks {
    fun unpack(
        longs: LongArray, nbits: Int, width: Int, height: Int, depth: Int,
    ): IntArray {
        val total = width.toLong() * height * depth
        require(total <= Int.MAX_VALUE) { "Volume exceeds Int.MAX_VALUE" }
        val out = IntArray(total.toInt())
        return when (nbits) {
            0 -> out.also { /* all air */ }
            4 -> unpack4(longs, out, width, height, depth)
            8 -> unpack8(longs, out, width, height, depth)
            else -> BlockStatePacker.unpack(longs, nbits, width, height, depth)
        }
    }

    private fun unpack4(longs: LongArray, out: IntArray, w: Int, h: Int, d: Int): IntArray {
        val cellsPerSlice = w.toLong() * d
        var longIdx = 0
        var bitPos = 0
        val mask = 0xFL
        for (y in 0 until h) {
            val yBase = y * cellsPerSlice.toInt()
            for (z in 0 until d) {
                val rowBase = yBase + z * w
                for (x in 0 until w) {
                    out[rowBase + x] = ((longs[longIdx] ushr bitPos) and mask).toInt()
                    bitPos += 4
                    if (bitPos >= 64) { bitPos = 0; longIdx++ }
                }
            }
        }
        return out
    }

    private fun unpack8(longs: LongArray, out: IntArray, w: Int, h: Int, d: Int): IntArray {
        val cellsPerSlice = w.toLong() * d
        var longIdx = 0
        var bitPos = 0
        val mask = 0xFFL
        for (y in 0 until h) {
            val yBase = y * cellsPerSlice.toInt()
            for (z in 0 until d) {
                val rowBase = yBase + z * w
                for (x in 0 until w) {
                    out[rowBase + x] = ((longs[longIdx] ushr bitPos) and mask).toInt()
                    bitPos += 8
                    if (bitPos >= 64) { bitPos = 0; longIdx++ }
                }
            }
        }
        return out
    }
}
```

- [ ] **Step 2: Create parity test**

```kotlin
package io.github.moxisuki.blockprint.core.internal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class PackedBlocksUnpackParityTest {
    @Test fun `4-bit matches BlockStatePacker`() {
        val w = 16; val h = 16; val d = 16
        val blocks = IntArray(w * h * d) { Random(42).nextInt(0, 16) }
        val packed = packFromInts(blocks, 4, w, h, d)
        val legacy = BlockStatePacker.unpack(packed, 4, w, h, d)
        val specialized = PackedBlocks.unpack(packed, 4, w, h, d)
        assertContentEquals(legacy, specialized)
    }

    @Test fun `8-bit matches BlockStatePacker`() {
        val w = 32; val h = 16; val d = 16
        val blocks = IntArray(w * h * d) { Random(7).nextInt(0, 256) }
        val packed = packFromInts(blocks, 8, w, h, d)
        val legacy = BlockStatePacker.unpack(packed, 8, w, h, d)
        val specialized = PackedBlocks.unpack(packed, 8, w, h, d)
        assertContentEquals(legacy, specialized)
    }

    @Test fun `fallback path for nbits=12 matches legacy`() {
        val w = 16; val h = 16; val d = 16
        val blocks = IntArray(w * h * d) { Random(1).nextInt(0, 1 shl 12) }
        val packed = packFromInts(blocks, 12, w, h, d)
        val legacy = BlockStatePacker.unpack(packed, 12, w, h, d)
        val specialized = PackedBlocks.unpack(packed, 12, w, h, d)
        assertContentEquals(legacy, specialized)
    }

    private fun packFromInts(blocks: IntArray, nbits: Int, w: Int, h: Int, d: Int): LongArray {
        val mask = (1L shl nbits) - 1L
        val total = blocks.size
        val longCount = (total.toLong() * nbits + 63) / 64
        val longs = LongArray(longCount.toInt())
        var longIdx = 0; var bitPos = 0
        for (v in blocks) {
            longs[longIdx] = longs[longIdx] or ((v.toLong() and mask) shl bitPos)
            bitPos += nbits
            if (bitPos >= 64) { bitPos = 0; longIdx++ }
        }
        return longs
    }
}
```

- [ ] **Step 3: Run parity test**

Run: `./gradlew jvmTest --tests "*PackedBlocksUnpackParityTest*" --console=plain`
Expected: All 3 pass.

- [ ] **Step 4: Wire `PackedBlocks.unpack` into `LitematicaReader`**

In `format/litematica/LitematicaReader.kt`, replace:
```kotlin
val blocks = BlockStatePacker.unpack(blockStatesTag.value, nbits, width, height, depth)
```
with:
```kotlin
val blocks = PackedBlocks.unpack(blockStatesTag.value, nbits, width, height, depth)
```

- [ ] **Step 5: Run all tests + benchmark**

Run: `./gradlew jvmTest --console=plain && ./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*" -i --console=plain | tee /tmp/bench-after-packed.txt`
Expected: tests pass; Litematica 50MB median improves.

- [ ] **Step 6: Verify ≥ 1.4× speedup; if not, revert step 4**

```bash
# Compare medians from /tmp/bench-before.txt and /tmp/bench-after-packed.txt.
# If after < before / 1.4, run: git revert HEAD --no-edit
```

- [ ] **Step 7: Commit (only if speedup met)**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/PackedBlocks.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/PackedBlocksUnpackParityTest.kt \
        src/commonMain/kotlin/io/github/moxisuki/blockprint/core/format/litematica/LitematicaReader.kt
git commit -m "perf(litematica): specialized 4/8-bit unpacking via PackedBlocks"
```

### Task 5.3: Add `NbtReader.readRoot(InputStream)` true streaming

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtReader.kt`
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/nbt/NbtReaderStreamReadTest.kt`

- [ ] **Step 1: Add true streaming readRoot**

In `NbtReader.kt`, replace the existing `readRoot(input: InputStream)` method:

```kotlin
fun readRoot(input: InputStream): NbtTag.CompoundTag = input.use { stream ->
    DataInputStream(openStreamFromInput(stream)).use { dis ->
        val tagId = dis.readByte()
        check(tagId == NbtTagType.Compound.id) {
            "Expected root NBT tag to be COMPOUND (10), got ${NbtTagType.fromId(tagId)}"
        }
        dis.readUTF()  // root name
        readCompound(dis)
    }
}

private fun openStreamFromInput(stream: InputStream): InputStream {
    val pb = java.io.PushbackInputStream(stream, 2)
    val head = ByteArray(2)
    val n = pb.read(head, 0, 2)
    pb.unread(head, 0, n)
    return if (n == 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) {
        java.util.zip.GZIPInputStream(pb)
    } else {
        pb
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileKotlinJvm --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Add stream-vs-bytes parity test**

```kotlin
package io.github.moxisuki.blockprint.core.nbt

import io.github.moxisuki.blockprint.core.NbtReader
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class NbtReaderStreamReadTest {
    @Test fun `stream and bytes paths produce equal root`() {
        val bytes = generateSimpleRoot()
        val fromBytes = NbtReader.readRoot(bytes)
        val fromStream = NbtReader.readRoot(ByteArrayInputStream(bytes))
        assertEquals(fromBytes, fromStream)
    }

    @Test fun `gzipped stream and gzipped bytes produce equal root`() {
        val payload = generateSimpleRoot()
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(payload) }
        val gz = baos.toByteArray()
        val fromBytes = NbtReader.readRoot(gz)
        val fromStream = NbtReader.readRoot(ByteArrayInputStream(gz))
        assertEquals(fromBytes, fromStream)
    }

    private fun generateSimpleRoot(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(baos)
        dos.writeByte(10)  // compound
        dos.writeUTF("")   // name
        dos.writeByte(8); dos.writeUTF("answer"); dos.writeInt(42)  // int tag
        dos.writeByte(0)   // end
        dos.flush()
        return baos.toByteArray()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew jvmTest --tests "*NbtReaderStreamReadTest*" --console=plain`
Expected: 2 pass

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtReader.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/nbt/NbtReaderStreamReadTest.kt
git commit -m "perf(nbt): NbtReader.readRoot(InputStream) avoids full readBytes()"
```

### Task 5.4: `BlockPrintReader.read(InputStream)` uses streaming path

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReader.kt`

- [ ] **Step 1: Replace the read(stream) path**

In `api/BlockPrintReader.kt`, change `read(input: InputStream)`:

```kotlin
@JvmStatic
fun read(input: InputStream): BlockPrintDocument = input.use { stream ->
    val root = NbtReader.readRoot(stream)  // streams; no readBytes
    parseRoot(root)
}
```

- [ ] **Step 2: Add `BlockPrintReaderReadStreamTest.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.model.BlockPrintDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockPrintReaderReadStreamTest {
    @Test fun `read from stream matches read from bytes`() {
        val file = File("src/jvmTest/resources/test/litematic/single-region.litematic")
        val fromBytes: BlockPrintDocument = BlockPrintReader.read(file.readBytes())
        val fromStream: BlockPrintDocument = file.inputStream().use { BlockPrintReader.read(it) }
        assertEquals(fromBytes.regions.size, fromStream.regions.size)
        assertEquals(fromBytes.name, fromStream.name)
    }
}
```

- [ ] **Step 3: Run tests + benchmark**

Run: `./gradlew jvmTest --tests "*api.*" --console=plain && ./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*" -i --console=plain | tee /tmp/bench-after-stream.txt`
Expected: tests pass; benchmark at least matches the packed-only result.

- [ ] **Step 4: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReader.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReaderReadStreamTest.kt
git commit -m "perf(api): BlockPrintReader.read(InputStream) uses streaming NbtReader"
```

### Task 5.5: Add `NbtReader.readRootHeader` for Peek skip-subtree

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtReader.kt`
- Create: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/nbt/NbtReaderSkipSubtreeTest.kt`

- [ ] **Step 1: Add `readRootHeader` and `skipPayload` to NbtReader**

In `NbtReader.kt`, add:

```kotlin
fun readRootHeader(
    bytes: ByteArray,
    skipSubtreeNames: Set<String>,
): NbtTag.CompoundTag = DataInputStream(
    if (isGzip(bytes)) java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
    else java.io.ByteArrayInputStream(bytes)
).use { dis ->
    val tagId = dis.readByte()
    check(tagId == NbtTagType.Compound.id) {
        "Expected root NBT tag to be COMPOUND (10), got ${NbtTagType.fromId(tagId)}"
    }
    dis.readUTF()
    readCompoundHeader(dis, skipSubtreeNames)
}

private fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

private fun readCompoundHeader(dis: DataInputStream, skipSubtreeNames: Set<String>): NbtTag.CompoundTag {
    val entries = mutableListOf<Pair<String, NbtTag>>()
    while (true) {
        val rawId = dis.readByte()
        val id = NbtTagType.fromId(rawId)
        if (id == NbtTagType.End) break
        val name = dis.readUTF()
        if (name in skipSubtreeNames) {
            skipPayload(dis, id)
            entries += name to NbtTag.EndTag
        } else {
            entries += name to readTagPayload(dis, id)
        }
    }
    return NbtTag.CompoundTag(entries)
}

private fun skipPayload(dis: DataInputStream, id: NbtTagType) {
    when (id) {
        NbtTagType.End -> {}
        NbtTagType.Byte -> dis.readByte()
        NbtTagType.Short -> dis.readShort()
        NbtTagType.Int -> dis.readInt()
        NbtTagType.Long -> dis.readLong()
        NbtTagType.Float -> dis.readFloat()
        NbtTagType.Double -> dis.readDouble()
        NbtTagType.ByteArray -> dis.skipNBytes(dis.readInt().toLong())
        NbtTagType.String -> { val n = dis.readUnsignedShort(); dis.skipNBytes(n.toLong()) }
        NbtTagType.List -> {
            val elementType = NbtTagType.fromId(dis.readByte())
            val length = dis.readInt()
            repeat(length) { skipPayload(dis, elementType) }
        }
        NbtTagType.Compound -> {
            while (true) {
                val next = NbtTagType.fromId(dis.readByte())
                if (next == NbtTagType.End) break
                dis.readUTF()
                skipPayload(dis, next)
            }
        }
        NbtTagType.IntArray -> { val n = dis.readInt(); dis.skipNBytes(n * 4L) }
        NbtTagType.LongArray -> { val n = dis.readInt(); dis.skipNBytes(n * 8L) }
    }
}
```

- [ ] **Step 2: Create parity test**

```kotlin
package io.github.moxisuki.blockprint.core.nbt

import io.github.moxisuki.blockprint.core.NbtReader
import io.github.moxisuki.blockprint.core.NbtTag
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class NbtReaderSkipSubtreeTest {
    @Test fun `skipping Regions produces same header fields as full read`() {
        val bytes = buildLitematicaHeader(
            name = "test-schematic", author = "tester", mcDataVersion = 3953,
            regionsBody = ByteArray(1024) { 0 },
        )
        val full = NbtReader.readRoot(bytes)
        val header = NbtReader.readRootHeader(bytes, skipSubtreeNames = setOf("Regions"))
        assertEquals((full.get("Name") as NbtTag.StringTag).value,
                     (header.get("Name") as NbtTag.StringTag).value)
        assertEquals((full.get("Author") as NbtTag.StringTag).value,
                     (header.get("Author") as NbtTag.StringTag).value)
        assertEquals((full.get("MinecraftDataVersion") as NbtTag.IntTag).value,
                     (header.get("MinecraftDataVersion") as NbtTag.IntTag).value)
    }

    @Test fun `skipping unknown name is no-op (full read)`() {
        val bytes = buildLitematicaHeader("a", "b", 1, ByteArray(0))
        val full = NbtReader.readRoot(bytes)
        val header = NbtReader.readRootHeader(bytes, skipSubtreeNames = setOf("DoesNotExist"))
        assertEquals(full.entries().keys, header.entries().keys)
    }

    private fun buildLitematicaHeader(
        name: String, author: String, mcDataVersion: Int, regionsBody: ByteArray,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(10); dos.writeUTF("")
        dos.writeByte(3); dos.writeUTF("MinecraftDataVersion"); dos.writeInt(mcDataVersion)
        dos.writeByte(8); dos.writeUTF("Name"); dos.writeUTF(name)
        dos.writeByte(8); dos.writeUTF("Author"); dos.writeUTF(author)
        dos.writeByte(10); dos.writeUTF("Regions")
        baos.write(regionsBody)
        dos.writeByte(0)
        dos.writeByte(0)
        return baos.toByteArray()
    }
}
```

- [ ] **Step 3: Run test**

Run: `./gradlew jvmTest --tests "*NbtReaderSkipSubtreeTest*" --console=plain`
Expected: 2 pass

- [ ] **Step 4: Wire `readRootHeader` into `BlockPrintReader.peek`**

In `api/BlockPrintReader.kt`, replace the `peek(bytes)` body:

```kotlin
@JvmStatic
fun peek(bytes: ByteArray): BlockPrintSummary {
    if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) return BuildingHelperReader.readHeader(bytes)
    val root = NbtReader.readRootHeader(bytes, skipSubtreeNames = setOf("Regions", "Schematic"))
    return when (FormatDetector.detect(root)) {
        SchematicFormat.Litematica -> LitematicaReader.readHeader(root)
        SchematicFormat.Sponge -> SpongeReader.readHeader(root)
        SchematicFormat.Structure -> StructureReader.readHeader(root)
        SchematicFormat.PartialNbt -> BlockPrintSummary(
            format = SchematicFormat.PartialNbt, name = "", author = "", description = "",
            version = null,
            minecraftDataVersion = io.github.moxisuki.blockprint.core.internal.NbtAccessors.readIntOrNull(root, "MinecraftDataVersion"),
        )
        SchematicFormat.Unknown -> BlockPrintSummary(SchematicFormat.Unknown, "", "", "", null, null)
        SchematicFormat.BuildingHelper -> error("BuildingHelper routed by byte sniff above")
    }
}
```

- [ ] **Step 5: Add `BlockPrintReaderPeekNoAllocationTest.kt`**

```kotlin
package io.github.moxisuki.blockprint.core.api

import io.github.moxisuki.blockprint.core.LitematicReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class BlockPrintReaderPeekNoAllocationTest {
    @Test fun `peek on 200MB fixture completes quickly`() {
        val fixture = "src/jvmTest/resources/test/benchmark/200mb.litematic"
        val file = File(fixture)
        assumeTrue(file.exists(), "200MB fixture missing: $fixture")

        val readNs = kotlin.system.measureNanoTime {
            runCatching { LitematicReader.read(file) }
        }
        val peekNs = kotlin.system.measureNanoTime {
            BlockPrintReader.peek(file)
        }
        println("[PEEK-ALLOC] read 200MB = ${readNs / 1_000_000} ms, peek = ${peekNs / 1_000_000} ms")
        assertTrue(peekNs * 10 < readNs, "peek not 10x faster than read: read=$readNs peek=$peekNs")
    }
}
```

- [ ] **Step 6: Run new tests + verify behavior**

Run: `./gradlew jvmTest --tests "*api.*Peek*" --console=plain`
Expected: all pass

- [ ] **Step 7: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtReader.kt \
        src/main/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReader.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/nbt/NbtReaderSkipSubtreeTest.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintReaderPeekNoAllocationTest.kt
git commit -m "perf(peek): NbtReader.readRootHeader skips Regions subtree"
```

---

## Phase 6: GLB subtree restructuring

**Goal:** Move GLB files into `glb/<sub>/` subpackages with new type names. Behavior unchanged.

### Task 6.1: Split `glb/` into subdirs

**Files:**
- Move: ~13 GLB files from `glb/` to `glb/{writer,mesh,model,texture,platform,internal,synthetic}/`

- [ ] **Step 1: Move files**

```bash
cd src/commonMain/kotlin/io/github/moxisuki/blockprint/core/glb
mkdir -p writer mesh model texture platform internal synthetic
# writer
git mv GlbWriter.kt GlbOutput.kt GlbExportOptions.kt writer/
# mesh
git mv MeshBuilder.kt MeshSink.kt RawMesh.kt mesh/
# model
git mv ModelResolver.kt CreateModObjAdapter.kt model/
# texture
git mv TexturePacker.kt texture/
# platform
git mv ImageBackend.kt OffHeapBuf.kt FileAccessor.kt platform/
# internal files remain in glb/internal/
# synthetic files remain in glb/synthetic/
```

- [ ] **Step 2: Update all package declarations + imports**

For each moved file, set the package to its new subpath. For example, `glb/writer/GlbWriter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.glb.writer
```

- [ ] **Step 3: Move GLB test files into mirror subdirs**

Same procedure under `src/jvmTest/.../glb/`. All 20+ test files.

- [ ] **Step 4: Move and rename `LitematicToGlb.kt` to `api/BlockPrintToGlb.kt`**

- [ ] **Step 5: Build + run all tests**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`. GLB behavior unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(glb): split into writer/mesh/model/texture/platform/internal/synthetic subdirs"
```

### Task 6.2: Update GLB type references

**Files:** All GLB source files (Litematic → BlockPrintDocument; LitematicRegion → BlockPrintRegion)

- [ ] **Step 1: Bulk find-and-replace**

In all glb/ files, replace `Litematic` with `BlockPrintDocument` and `LitematicRegion` with `BlockPrintRegion`. (Be careful not to touch `Litematica` which is the spec name in some places.) Use IDE find-and-replace scoped to glb/ subdir.

- [ ] **Step 2: Build + test**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor(glb): use BlockPrintDocument/BlockPrintRegion in GLB layer"
```

---

## Phase 7: Cleanup — delete old, update docs, bump version

**Goal:** Remove legacy `Litematic*` classes and old `internal/format/` writers. Update README and API docs. Bump to 1.0.0.

### Task 7.1: Update `BlockPrintConverter` to use new readers

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintConverter.kt`

- [ ] **Step 1: Replace `Litematic` with `BlockPrintDocument` in Converter**

Replace all `Litematic` references in the file with `BlockPrintDocument`, and `LitematicRegion` with `BlockPrintRegion`. Update `convert(source: Litematic, ...)` signatures to `convert(source: BlockPrintDocument, ...)`.

- [ ] **Step 2: Build + test**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/api/BlockPrintConverter.kt
git commit -m "refactor(api): BlockPrintConverter uses BlockPrintDocument"
```

### Task 7.2: Delete legacy `Litematic*` classes and old writer paths

**Files:**
- Delete: `LitematicReader.kt`, `Litematic.kt`, `LitematicRegion.kt`, `LitematicException.kt`, `BlueprintConverter.kt`, `LitematicToGlb.kt`
- Delete: `internal/LitematicParser.kt`, `internal/BuildingHelperParser.kt`, `internal/format/` (4 files)
- Delete: `glb/LitematicToGlb.kt` (replaced by `api/BlockPrintToGlb.kt`)

- [ ] **Step 1: Delete the files**

```bash
cd src/commonMain/kotlin/io/github/moxisuki/blockprint/core
git rm LitematicReader.kt Litematic.kt LitematicRegion.kt LitematicException.kt
git rm internal/LitematicParser.kt internal/BuildingHelperParser.kt
rm -rf internal/format/
git rm glb/LitematicToGlb.kt
```

- [ ] **Step 2: Find any remaining `Litematic*` references in src/**

```bash
grep -rn "LitematicReader\|Litematic\b\|LitematicRegion\|LitematicToGlb\|LitematicException\|LitematicParser\|BuildingHelperParser" src/ || echo "clean"
```

- [ ] **Step 3: Update remaining references**

If any references remain in non-deleted files, update them to BlockPrint* equivalents.

- [ ] **Step 4: Build + test**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`. The build is now fully on the new types.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove legacy Litematic* classes and forwarders"
```

### Task 7.3: Update README.md and BLUEPRINT_API.md

**Files:**
- Modify: `README.md`, `README_EN.md`
- Modify: `docs/BLUEPRINT_API.md`, `docs/BLUEPRINT_API_EN.md`

- [ ] **Step 1: Update README.md**

- Change `LitematicReader.read(...)` examples to `BlockPrintReader.read(...)`
- Change `LitematicToGlb.convert(...)` to `BlockPrintToGlb.convert(...)`
- Change `val lit = LitematicReader.read(...)` to `val doc = BlockPrintReader.read(...)`
- Change `MaterialList.from(lit)` to `MaterialList.from(doc)`
- Add a "Peek" section showing `BlockPrintReader.peek(File("..."))` example

- [ ] **Step 2: Update README_EN.md**

Same as Step 1.

- [ ] **Step 3: Update BLUEPRINT_API.md**

Replace all `LitematicReader` → `BlockPrintReader`, `Litematic` → `BlockPrintDocument`, etc. Add Peek section.

- [ ] **Step 4: Update BLUEPRINT_API_EN.md**

Same as Step 3.

- [ ] **Step 5: Verify no stale Litematic* references in docs**

```bash
grep -rn "LitematicReader\|Litematic\b\|LitematicRegion\|LitematicToGlb\|LitematicException\|LitematicParser" docs/ README*.md 2>/dev/null || echo "docs clean"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: update README and BLUEPRINT_API for BlockPrint 1.0"
```

### Task 7.4: Bump version 0.2.2 → 1.0.0

**Files:**
- Modify: `gradle.properties` (or wherever the version is set)
- Modify: any CHANGELOG.md

- [ ] **Step 1: Find the version file**

```bash
grep -rn "0.2.2" gradle.properties build.gradle.kts 2>/dev/null | head -5
```

- [ ] **Step 2: Bump version**

In whichever file holds it, change `version = "0.2.2"` to `version = "1.0.0"`.

- [ ] **Step 3: Add CHANGELOG entry**

In `CHANGELOG.md` (create if absent), add:

```markdown
# 1.0.0 (2026-07-02)

## Breaking changes
- `LitematicReader` → `BlockPrintReader` (package `io.github.moxisuki.blockprint.core.api`)
- `Litematic` → `BlockPrintDocument` (package `io.github.moxisuki.blockprint.core.model`)
- `LitematicRegion` → `BlockPrintRegion`
- `LitematicException` → `BlockPrintException`
- `LitematicToGlb` → `BlockPrintToGlb`
- `BlueprintConverter` → `BlockPrintConverter`
- Package layout: `internal/format/` → `format/<formatName>/`; `internal/LitematicParser.kt` split into per-format readers

## New features
- `BlockPrintReader.peek(...)` returns `BlockPrintSummary` (header-only read, skips block data)

## Performance
- Litematica parsing: ≥ 1.4× faster (specialized 4/8-bit unpacking)
- Sponge v3 parsing: ≥ 1.6× faster (shared VarInt codec, optimized loops)
- `BlockPrintReader.read(InputStream)` no longer materializes the full byte array
```

- [ ] **Step 4: Build + test**

Run: `./gradlew jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit + tag**

```bash
git add -A
git commit -m "chore(release): bump to 1.0.0"
git tag -a v1.0.0 -m "BlockPrint 1.0.0 release"
```

### Task 7.5: Final pre-release verification

- [ ] **Step 1: Clean build**

Run: `./gradlew clean jvmTest --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run benchmark one more time, attach to release notes**

Run: `./gradlew jvmTest --tests "*BlockPrintParsingBenchmark*" -i --console=plain | tee /tmp/bench-final.txt`

- [ ] **Step 3: Push branch and open PR**

```bash
git push -u origin rewrite/blueprint-reader
gh pr create --base master --title "BlockPrint 1.0: rename + restructure + peek + perf" --body "..."
```

---

## Self-Review

**1. Spec coverage:**

| Spec section | Task(s) |
|---|---|
| §1 architecture & package layout | 1.1, 1.2, 2.1, 3.1–3.8, 6.1 |
| §2 rename mapping | 1.1, 1.3, 2.1, 3.4–3.6, 6.2, 7.1, 7.2 |
| §2 public API surface | 4.1, 4.2 |
| §3.1 streaming entry | 5.3, 5.4 |
| §3.2 PackedBlocks | 5.2 |
| §3.3 shared VarInt | 3.7 |
| §3.4 FormatDetector | 3.3 |
| §3.5 Structure sparse→dense | 3.6 (no change) |
| §3.6 BlockState interning | explicitly NOT done |
| §4 Peek API | 4.1, 4.2, 5.5 |
| §4 NbtReader short-circuit | 5.5 |
| §4 BuildingHelper short-circuit | 3.6 (substring optimization explicitly deferred) |
| §5 error handling | 3.2, 4.1, 4.2 |
| §6 test strategy | 1.4, 2.2, 3.3, 4.2, 5.1, 5.2, 5.3, 5.5 |
| §7 implementation order | All phases 0–7 |

**2. Placeholder scan:** No "TBD" / "TODO" / "implement later" in task bodies. The two exceptions:
- Task 3.6 step 3 has `TODO("Lift implementation from BuildingHelperParser in Task 3.6 step 4")` — this is the placeholder for the executor to do the actual code lift; it's not a plan-level TODO.
- Task 5.1 step 2 has `# TODO: actual fixture generation step at execution time.` — this is acknowledged (fixture generation requires existing large files).
- Task 3.7 says "this is Phase 5 work but is needed to compile SpongeReader" — moved here to keep SpongeReader compilable.

These are acceptable because the executor (subagent) will handle them.

**3. Type consistency:**
- `BlockPrintDocument.regions: List<BlockPrintRegion>` — used consistently in 1.3, 3.4–3.6, 6.2
- `BlockPrintSummary` — used consistently in 3.4–3.6, 4.1, 4.2, 5.5
- `BlockPrintException` — used consistently in 3.1, 3.2, 3.4–3.6
- `VarInt.decode` returns `Result(value, nextPos)` — used in 3.6 SpongeReader
- `PackedBlocks.unpack(longs, nbits, w, h, d)` — signature matches `BlockStatePacker.unpack` (called as fallback in PackedBlocks)

**4. Rollback strategy:** Each phase commits independently. Reverting a single phase means `git revert <commit>` for that phase. Phase 5 has explicit revert path: "Verify ≥ 1.4× speedup; if not, revert step 4."

**5. Risk notes:**
- Phase 3 task 3.6 step 3 has the largest manual code lift (BuildingHelperReader). The executor should test the lift against the existing BHParserStandardFormatTest fixtures.
- Phase 5 task 5.1 (benchmark) requires fixture files. If 50MB/200MB Litematica fixtures don't exist in the repo, the executor must generate them or skip the benchmark until they exist.
- Phase 7 task 7.2 (delete legacy) is destructive. The executor must run the grep in step 2 BEFORE deleting to find any hidden references.
