# Blueprint Format Conversion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `BlueprintConverter` facade that converts between Litematica, Sponge, vanilla Structure, and BuildingHelper blueprint formats in any direction, with `Litematic` as the in-memory canonical model.

**Architecture:** Build a low-level `NbtWriter` (mirror of `NbtReader`), then four internal format encoders (`LitematicWriter`, `SpongeWriter`, `StructureWriter`, `BuildingHelperWriter`). A single `BlueprintConverter` facade dispatches to the right encoder by target `SchematicFormat`. Read path is untouched.

**Tech Stack:** Kotlin 2.2.10, Kotlin Multiplatform (commonMain + jvmTest), `java.util.zip.GZIPOutputStream`, `java.io.DataOutputStream`, JUnit 4 (per existing tests).

**Spec:** `docs/superpowers/specs/2026-06-21-blueprint-conversion-design.md`

**Test command pattern (all tasks):**
```bash
./gradlew jvmTest --tests "<fully.qualified.TestClass>.<method>" --info
```

---

## File Structure

**Created:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverter.kt` — public facade
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtWriter.kt` — public low-level NBT byte writer
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriter.kt`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriter.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/NbtWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriterTest.kt`
- `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterTest.kt`

**Modified:**
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/SchematicFormat.kt` — add `fromExtension(String)`
- `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePacker.kt` — add `pack(...)`
- `docs/BLUEPRINT_API.md` — document `BlueprintConverter`
- `docs/BLUEPRINT_API_EN.md` — document `BlueprintConverter`

**Untouched:** `LitematicReader`, `LitematicParser`, `BuildingHelperParser`, `NbtReader`, `NbtTag`, `NbtDocument`, `Litematic`, `LitematicRegion`.

---

## Task 1: `BlockStatePacker.pack` — bit-packing inverse

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePacker.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePackerPackTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePackerPackTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BlockStatePackerPackTest {

    @Test
    fun pack_then_unpack_is_identity() {
        val rng = Random(42)
        for (trial in 0 until 20) {
            val width = 1 + rng.nextInt(5)
            val height = 1 + rng.nextInt(4)
            val depth = 1 + rng.nextInt(5)
            val paletteSize = 1 + rng.nextInt(15)
            val nbits = BlockPaletteBits.bitsNeeded(paletteSize)
            val blocks = IntArray(width * height * depth) { rng.nextInt(paletteSize) }
            val packed = BlockStatePacker.pack(blocks, nbits, width, height, depth)
            val unpacked = BlockStatePacker.unpack(packed, nbits, width, height, depth)
            assertArrayEquals(blocks, unpacked)
        }
    }

    @Test
    fun pack_all_zeros_yields_zeros() {
        val blocks = IntArray(2 * 3 * 4)
        val packed = BlockStatePacker.pack(blocks, nbits = 1, width = 2, height = 3, depth = 4)
        // 1 bit per block * 24 blocks = 24 bits = 1 long (with padding zeros)
        assertEquals(1, packed.size)
        assertEquals(0L, packed[0])
    }

    @Test
    fun pack_little_endian_bit_order() {
        // 2 blocks, nbits=4, blocks=[0xA, 0x5]
        // bit 0..3 = 0xA = 0b1010
        // bit 4..7 = 0x5 = 0b0101
        // long LSB-first: 0x5A
        val blocks = intArrayOf(0xA, 0x5)
        val packed = BlockStatePacker.pack(blocks, nbits = 4, width = 2, height = 1, depth = 1)
        assertEquals(1, packed.size)
        assertEquals(0x5AL, packed[0])
    }
}

private object BlockPaletteBits {
    fun bitsNeeded(paletteSize: Int): Int =
        if (paletteSize <= 1) 1 else (0..30).first { (1 shl it) >= paletteSize }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.BlockStatePackerPackTest" --info`
Expected: compile error — `BlockStatePacker.pack` and `BlockPaletteBits` not found.

- [ ] **Step 3: Add `pack` to `BlockStatePacker`**

In `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePacker.kt`, add this method directly after `unpack(...)` (before `validateLength(...)`):

```kotlin
    /**
     * Pack a dense y-major block-index array into the Litematica
     * `BlockStates` long-array encoding (inverse of [unpack]).
     *
     * Layout matches [unpack]: y-major, z-middle, x-minor. Each block
     * is `nbits` wide, packed LSB-first across the long array. Any
     * trailing bits in the last long are zero (the reader tolerates
     * this; see [validateLength]).
     */
    fun pack(
        blocks: IntArray,
        nbits: Int,
        width: Int,
        height: Int,
        depth: Int,
    ): LongArray {
        require(nbits in 0..64) { "nbits must be in [0, 64], got $nbits" }
        require(width >= 0 && height >= 0 && depth >= 0) {
            "Dimensions must be non-negative, got ${width}x${height}x${depth}"
        }
        if (nbits == 0) {
            // 0-bit palette: every field is 0, the long array is unused
            // by the reader. We still emit one long so validateLength passes
            // for at least one cell.
            return LongArray(1)
        }
        val total = (width.toLong() * height.toLong() * depth.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(blocks.size >= total) {
            "Block array size ${blocks.size} is smaller than declared $width*$height*$depth = $total"
        }
        val requiredBits = total.toLong() * nbits.toLong()
        val longCount = ((requiredBits + 63) / 64).toInt().coerceAtLeast(1)
        val out = LongArray(longCount)
        val yShift = width * depth
        val zShift = width
        for (y in 0 until height) {
            val yBase = y * yShift
            for (z in 0 until depth) {
                val zBase = yBase + z * zShift
                for (x in 0 until width) {
                    val index = zBase + x
                    val value = blocks[index].toLong() and ((1L shl nbits) - 1L)
                    val bitOffset = index.toLong() * nbits.toLong()
                    val longIndex = (bitOffset ushr 6).toInt()
                    val intraBit = (bitOffset and 0x3F).toInt()
                    out[longIndex] = out[longIndex] or (value shl intraBit)
                    if (intraBit + nbits > 64) {
                        val overflowBits = intraBit + nbits - 64
                        out[longIndex + 1] = out[longIndex + 1] or (value ushr (nbits - overflowBits))
                    }
                }
            }
        }
        return out
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.BlockStatePackerPackTest" --info`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePacker.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/BlockStatePackerPackTest.kt
git commit -m "feat(packer): add pack() inverse of unpack with bit-order test"
```

---

## Task 2: `NbtWriter` — low-level NBT byte writer

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtWriter.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/NbtWriterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/NbtWriterTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class NbtWriterTest {

    @Test
    fun writeRootToBytes_produces_byte_equivalent_to_hand_crafted_payload() {
        // Hand-crafted minimal NBT: root compound named "" containing a single
        // IntTag("answer") = 42. Then a trailing End tag.
        // Spec: 1B tag id (0x0A compound), 2B name length (0x0000 ""), then the
        // single entry's payload: 1B id (0x03 int), 2B name "answer" (0x0006 0x61 0x6E 0x73 0x77 0x65 0x72),
        // 4B big-endian int 42, then 1B end tag (0x00).
        val expected = byteArrayOf(
            0x0A, 0x00, 0x00,
            0x03, 0x00, 0x06, 0x61, 0x6E, 0x73, 0x77, 0x65, 0x72,
            0x00, 0x00, 0x00, 0x2A,
            0x00,
        )
        val root = NbtTag.CompoundTag(
            listOf("answer" to NbtTag.IntTag(42)),
        )
        assertArrayEquals(expected, NbtWriter.writeRootToBytes(root))
    }

    @Test
    fun roundtrip_through_NbtReader_recovers_tree() {
        val tree = NbtTag.CompoundTag(
            listOf(
                "str" to NbtTag.StringTag("hi"),
                "i" to NbtTag.IntTag(-7),
                "l" to NbtTag.LongTag(1L shl 40),
                "f" to NbtTag.FloatTag(1.5f),
                "d" to NbtTag.DoubleTag(3.25),
                "ba" to NbtTag.ByteArrayTag(byteArrayOf(1, 2, 3)),
                "ia" to NbtTag.IntArrayTag(intArrayOf(10, 20)),
                "la" to NbtTag.LongArrayTag(longArrayOf(100L, 200L)),
                "emptyList" to NbtTag.ListTag(NbtTagType.End, emptyList()),
                "nested" to NbtTag.CompoundTag(
                    listOf("inside" to NbtTag.ByteTag(0x7F)),
                ),
            ),
        )
        val bytes = NbtWriter.writeRootToBytes(tree)
        val parsed = NbtReader.readRoot(bytes)
        // Compare by re-serializing; structural equals is what matters.
        assertArrayEquals(bytes, NbtWriter.writeRootToBytes(parsed))
    }

    @Test
    fun writeRootToGzipBytes_starts_with_gzip_magic() {
        val root = NbtTag.CompoundTag(listOf("x" to NbtTag.IntTag(1)))
        val bytes = NbtWriter.writeRootToGzipBytes(root)
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
        // The bytes must be a valid NBT root when decompressed.
        val parsed = NbtReader.readRoot(bytes)
        assertEquals(NbtTag.IntTag(1), parsed.get("x"))
    }

    @Test
    fun write_to_DataOutputStream_appends_to_existing_stream() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(0x00, 0x00, 0xFF.toByte()))
        val root = NbtTag.CompoundTag(listOf("k" to NbtTag.IntTag(7)))
        NbtWriter.writeRoot(root, baos)
        val full = baos.toByteArray()
        // First three bytes are the prefix we wrote.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0xFF.toByte()), full.copyOfRange(0, 3))
        // Remaining bytes must round-trip through the reader as a fresh root.
        val rest = full.copyOfRange(3, full.size)
        val parsed = NbtReader.readRoot(rest)
        assertEquals(NbtTag.IntTag(7), parsed.get("k"))
    }

    @Test
    fun writeRoot_throws_for_NonCompoundRoot() {
        try {
            // @Suppress needed: we want to test the runtime check.
            @Suppress("UNCHECKED_CAST")
            NbtWriter.writeRootToBytes(NbtTag.IntTag(1) as NbtTag.CompoundTag)
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.NbtWriterTest" --info`
Expected: compile error — `NbtWriter` not found.

- [ ] **Step 3: Implement `NbtWriter`**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtWriter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Public NBT (Named Binary Tag) serializer. Writes a single root tag
 * — a [NbtTag.CompoundTag] — to a stream or byte payload.
 *
 * Mirrors [NbtReader]: same tag-id order, same string encoding
 * (modified UTF-8, via [DataOutputStream.writeUTF]), same big-endian
 * numerics. Auto-wraps with GZIP only when the caller asks for it
 * via [writeRootToGzipBytes]; the stream-based [writeRoot] and the
 * [writeRootToBytes] variants emit raw NBT.
 */
object NbtWriter {

    /**
     * Serialize [root] as a named compound NBT root into [out]. The
     * root name is written as the empty string (matching vanilla NBT
     * files that [NbtReader.readRoot] accepts).
     */
    fun writeRoot(root: NbtTag.CompoundTag, out: OutputStream) {
        DataOutputStream(out).use { dos ->
            dos.writeByte(NbtTagType.Compound.id.toInt())
            dos.writeUTF("") // root name
            writeCompoundBody(root, dos)
        }
    }

    /** Convenience: [writeRoot] into a fresh `ByteArrayOutputStream`. */
    fun writeRootToBytes(root: NbtTag.CompoundTag): ByteArray {
        val baos = ByteArrayOutputStream()
        writeRoot(root, baos)
        return baos.toByteArray()
    }

    /** Convenience: [writeRoot] wrapped in a [GZIPOutputStream]. */
    fun writeRootToGzipBytes(root: NbtTag.CompoundTag): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz -> writeRoot(root, gz) }
        return baos.toByteArray()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun writeCompoundBody(c: NbtTag.CompoundTag, dos: DataOutputStream) {
        for ((name, tag) in c.value) {
            writeNamedTag(name, tag, dos)
        }
        dos.writeByte(NbtTagType.End.id.toInt())
    }

    private fun writeNamedTag(name: String, tag: NbtTag, dos: DataOutputStream) {
        when (tag) {
            is NbtTag.EndTag -> dos.writeByte(NbtTagType.End.id.toInt())
            is NbtTag.ByteTag -> {
                dos.writeByte(NbtTagType.Byte.id.toInt())
                dos.writeUTF(name); dos.writeByte(tag.value.toInt())
            }
            is NbtTag.ShortTag -> {
                dos.writeByte(NbtTagType.Short.id.toInt())
                dos.writeUTF(name); dos.writeShort(tag.value.toInt())
            }
            is NbtTag.IntTag -> {
                dos.writeByte(NbtTagType.Int.id.toInt())
                dos.writeUTF(name); dos.writeInt(tag.value)
            }
            is NbtTag.LongTag -> {
                dos.writeByte(NbtTagType.Long.id.toInt())
                dos.writeUTF(name); dos.writeLong(tag.value)
            }
            is NbtTag.FloatTag -> {
                dos.writeByte(NbtTagType.Float.id.toInt())
                dos.writeUTF(name); dos.writeFloat(tag.value)
            }
            is NbtTag.DoubleTag -> {
                dos.writeByte(NbtTagType.Double.id.toInt())
                dos.writeUTF(name); dos.writeDouble(tag.value)
            }
            is NbtTag.ByteArrayTag -> {
                dos.writeByte(NbtTagType.ByteArray.id.toInt())
                dos.writeUTF(name)
                dos.writeInt(tag.value.size)
                dos.write(tag.value)
            }
            is NbtTag.StringTag -> {
                dos.writeByte(NbtTagType.String.id.toInt())
                dos.writeUTF(name); dos.writeUTF(tag.value)
            }
            is NbtTag.ListTag -> {
                dos.writeByte(NbtTagType.List.id.toInt())
                dos.writeUTF(name)
                dos.writeByte(tag.elementType.id.toInt())
                dos.writeInt(tag.value.size)
                for (item in tag.value) writeListItem(item, dos)
            }
            is NbtTag.CompoundTag -> {
                dos.writeByte(NbtTagType.Compound.id.toInt())
                dos.writeUTF(name)
                writeCompoundBody(tag, dos)
            }
            is NbtTag.IntArrayTag -> {
                dos.writeByte(NbtTagType.IntArray.id.toInt())
                dos.writeUTF(name)
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeInt(v)
            }
            is NbtTag.LongArrayTag -> {
                dos.writeByte(NbtTagType.LongArray.id.toInt())
                dos.writeUTF(name)
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeLong(v)
            }
        }
    }

    private fun writeListItem(tag: NbtTag, dos: DataOutputStream) {
        when (tag) {
            is NbtTag.EndTag -> {} // never written inside a list
            is NbtTag.ByteTag -> dos.writeByte(tag.value.toInt())
            is NbtTag.ShortTag -> dos.writeShort(tag.value.toInt())
            is NbtTag.IntTag -> dos.writeInt(tag.value)
            is NbtTag.LongTag -> dos.writeLong(tag.value)
            is NbtTag.FloatTag -> dos.writeFloat(tag.value)
            is NbtTag.DoubleTag -> dos.writeDouble(tag.value)
            is NbtTag.ByteArrayTag -> {
                dos.writeInt(tag.value.size); dos.write(tag.value)
            }
            is NbtTag.StringTag -> dos.writeUTF(tag.value)
            is NbtTag.ListTag -> {
                dos.writeByte(tag.elementType.id.toInt())
                dos.writeInt(tag.value.size)
                for (item in tag.value) writeListItem(item, dos)
            }
            is NbtTag.CompoundTag -> writeCompoundBody(tag, dos)
            is NbtTag.IntArrayTag -> {
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeInt(v)
            }
            is NbtTag.LongArrayTag -> {
                dos.writeInt(tag.value.size)
                for (v in tag.value) dos.writeLong(v)
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.NbtWriterTest" --info`
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/NbtWriter.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/NbtWriterTest.kt
git commit -m "feat(nbt): add NbtWriter mirroring NbtReader with gzip helper"
```

---

## Task 3: `SchematicFormat.fromExtension` — file-extension routing

**Files:**
- Modify: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/SchematicFormat.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/SchematicFormatFromExtensionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/SchematicFormatFromExtensionTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertEquals
import org.junit.Test

class SchematicFormatFromExtensionTest {

    @Test
    fun litematic_maps_to_Litematica() {
        assertEquals(SchematicFormat.Litematica, SchematicFormat.fromExtension("litematic"))
        assertEquals(SchematicFormat.Litematica, SchematicFormat.fromExtension(".litematic"))
        assertEquals(SchematicFormat.Litematica, SchematicFormat.fromExtension("LITEMATIC"))
    }

    @Test
    fun schematic_maps_to_Sponge() {
        assertEquals(SchematicFormat.Sponge, SchematicFormat.fromExtension("schematic"))
    }

    @Test
    fun nbt_maps_to_Structure() {
        assertEquals(SchematicFormat.Structure, SchematicFormat.fromExtension("nbt"))
    }

    @Test
    fun json_maps_to_BuildingHelper() {
        assertEquals(SchematicFormat.BuildingHelper, SchematicFormat.fromExtension("json"))
    }

    @Test(expected = LitematicException::class)
    fun unknown_extension_throws() {
        SchematicFormat.fromExtension("bin")
    }

    @Test(expected = LitematicException::class)
    fun empty_extension_throws() {
        SchematicFormat.fromExtension("")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.SchematicFormatFromExtensionTest" --info`
Expected: compile error — `fromExtension` not found.

- [ ] **Step 3: Add `fromExtension` to `SchematicFormat`**

In `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/SchematicFormat.kt`, add an import at the top:

```kotlin
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
```

And add this method inside the `companion object` (after the existing `fromNbtRoot` method):

```kotlin
        /**
         * Resolve a format from a filename extension. Accepts both bare
         * (`"litematic"`) and dotted (`".litematic"`) forms; case-insensitive.
         *
         * @throws LitematicException for unknown extensions.
         */
        @JvmStatic
        fun fromExtension(ext: String): SchematicFormat {
            val normalized = ext.trim().lowercase().removePrefix(".")
            return when (normalized) {
                "litematic" -> Litematica
                "schematic" -> Sponge
                "nbt" -> Structure
                "json" -> BuildingHelper
                else -> throw LitematicException(
                    "Cannot infer schematic format from extension '.$ext' " +
                        "(expected one of: .litematic, .schematic, .nbt, .json)",
                )
            }
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.SchematicFormatFromExtensionTest" --info`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/SchematicFormat.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/SchematicFormatFromExtensionTest.kt
git commit -m "feat(format): add SchematicFormat.fromExtension with case-insensitive matching"
```

---

## Task 4: `LitematicWriter` — Litematica format encoder

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriter.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriterTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LitematicWriterTest {

    private fun buildSampleLitematic(): Litematic {
        // 2x1x2 region with 4 distinct block types + air.
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
                BlockState("minecraft:grass_block", mapOf("snowy" to "false")),
            ),
        )
        // y-major: [stone, dirt, grass, stone]
        val blocks = intArrayOf(1, 2, 3, 1)
        val region = LitematicRegion(
            name = "Sample",
            width = 2, height = 1, depth = 2,
            position = Position(10, 64, -5),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = 6,
            name = "Sample Build",
            author = "Tester",
            description = "unit test",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun write_then_read_round_trips_region() {
        val original = buildSampleLitematic()
        val bytes = LitematicWriter.write(original)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals("Sample", r.name)
        assertEquals(2, r.width); assertEquals(1, r.height); assertEquals(2, r.depth)
        assertEquals(Position(10, 64, -5), r.position)
        assertEquals(4, r.palette.size)
        assertArrayEquals(intArrayOf(1, 2, 3, 1), r.rawBlocks)
        assertEquals(3465, read.minecraftDataVersion)
        assertEquals(6, read.version)
        assertEquals("Sample Build", read.name)
        assertEquals("Tester", read.author)
    }

    @Test
    fun write_produces_gzipped_output() {
        val bytes = LitematicWriter.write(buildSampleLitematic())
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
    }

    @Test
    fun write_multi_region_preserves_all() {
        val lit = buildSampleLitematic().copy(
            regions = lit ->
                @Suppress("UNUSED_VARIABLE")
                val first = lit.regions.single()
                lit.regions + listOf(
                    LitematicRegion(
                        name = "Second",
                        width = 1, height = 1, depth = 1,
                        position = Position(0, 0, 0),
                        palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
                        blocks = intArrayOf(1),
                    ),
                )
        )
        val bytes = LitematicWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertEquals(2, read.regions.size)
        assertEquals(listOf("Sample", "Second"), read.regions.map { it.name })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.LitematicWriterTest" --info`
Expected: compile error — `LitematicWriter` not found.

- [ ] **Step 3: Implement `LitematicWriter`**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.internal.BlockStatePacker

/**
 * Encode a [Litematic] as a `.litematic` (gzipped NBT) byte payload.
 *
 * Output schema mirrors what [io.github.moxisuki.blockprint.core.internal.LitematicParser]
 * reads, so a `write → read` round-trip is structurally stable.
 */
internal object LitematicWriter {

    /** Default `MinecraftDataVersion` to write when the input is null. 3465 ≈ 1.21. */
    private const val DEFAULT_DATA_VERSION = 3465

    /** Default Litematica file format version. */
    private const val DEFAULT_FORMAT_VERSION = 6

    fun write(source: Litematic): ByteArray {
        val root = buildRoot(source)
        return NbtWriter.writeRootToGzipBytes(root)
    }

    private fun buildRoot(source: Litematic): NbtTag.CompoundTag {
        val regions = source.regions.map { (name, region) -> name to buildRegion(region) }
        val entries = buildList<Pair<String, NbtTag>> {
            add("MinecraftDataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION))
            add("Version" to NbtTag.IntTag(source.version ?: DEFAULT_FORMAT_VERSION))
            add("Name" to NbtTag.StringTag(source.name))
            add("Author" to NbtTag.StringTag(source.author))
            add("Description" to NbtTag.StringTag(source.description))
            add("Regions" to NbtTag.CompoundTag(regions))
        }
        return NbtTag.CompoundTag(entries)
    }

    private fun buildRegion(region: LitematicRegion): NbtTag.CompoundTag {
        val positionCompound = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.position.x),
                "y" to NbtTag.IntTag(region.position.y),
                "z" to NbtTag.IntTag(region.position.z),
            ),
        )
        val sizeCompound = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.width),
                "y" to NbtTag.IntTag(region.height),
                "z" to NbtTag.IntTag(region.depth),
            ),
        )
        val paletteList = NbtTag.ListTag(
            elementType = io.github.moxisuki.blockprint.core.NbtTagType.Compound,
            value = region.palette.entries.map { blockStateToCompound(it) },
        )
        val nbits = region.palette.bitsPerBlock
        val packed = BlockStatePacker.pack(region.rawBlocks, nbits, region.width, region.height, region.depth)
        val blockStates = NbtTag.LongArrayTag(packed)
        return NbtTag.CompoundTag(
            listOf(
                "Position" to positionCompound,
                "Size" to sizeCompound,
                "BlockStatePalette" to paletteList,
                "BlockStates" to blockStates,
            ),
        )
    }

    private fun blockStateToCompound(state: BlockState): NbtTag.CompoundTag {
        val props = state.properties
        val propsCompound: NbtTag.CompoundTag? = if (props.isNullOrEmpty()) null
        else NbtTag.CompoundTag(props.entries.map { (k, v) -> k to NbtTag.StringTag(v) })
        val entries = buildList<Pair<String, NbtTag>> {
            add("Name" to NbtTag.StringTag(state.name))
            if (propsCompound != null) add("Properties" to propsCompound)
        }
        return NbtTag.CompoundTag(entries)
    }
}
```

Note: the `write_multi_region_preserves_all` test above uses a `copy(regions = lit -> ...)` lambda. The test in step 1 will NOT compile until we adjust it to a real-world pattern. Replace the body of that test with a real Litematic construction:

```kotlin
    @Test
    fun write_multi_region_preserves_all() {
        val first = buildSampleLitematic().regions.single()
        val second = LitematicRegion(
            name = "Second",
            width = 1, height = 1, depth = 1,
            position = Position(0, 0, 0),
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val lit = buildSampleLitematic().copy(regions = listOf(first, second))
        val bytes = LitematicWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertEquals(2, read.regions.size)
        assertEquals(listOf("Sample", "Second"), read.regions.map { it.name })
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.LitematicWriterTest" --info`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriter.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/LitematicWriterTest.kt
git commit -m "feat(format): add LitematicWriter with gzip output and multi-region support"
```

---

## Task 5: `StructureWriter` — vanilla `/structure save` encoder

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriter.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriterTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureWriterTest {

    private fun sampleLitematic(): Litematic {
        // 1x2x1 region. y-major layout for blocks:
        //   index 0 = air
        //   index 1 = stone
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
            ),
        )
        val blocks = intArrayOf(0, 1) // y=0 air, y=1 stone
        val region = LitematicRegion(
            name = "Structure",
            width = 1, height = 2, depth = 1,
            position = Position.ZERO,
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = null,
            name = "x",
            author = "",
            description = "",
            regions = listOf(region),
            format = SchematicFormat.Structure,
        )
    }

    @Test
    fun write_then_read_via_lenient_round_trips() {
        val lit = sampleLitematic()
        val bytes = StructureWriter.write(lit)
        val read = LitematicReader.readLenient(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(1, r.width); assertEquals(2, r.height); assertEquals(1, r.depth)
        assertEquals(1, r.palette.size) // only stone; air is dropped from the output palette
        assertEquals("minecraft:stone", r.palette[0].name)
        assertEquals(1, r.rawBlocks[0])
        assertEquals(0, r.rawBlocks[1])
    }

    @Test
    fun write_produces_gzipped_output() {
        val bytes = StructureWriter.write(sampleLitematic())
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
    }

    @Test
    fun write_omits_air_cells_from_sparse_blocks() {
        // All-air region → empty blocks list (sparse).
        val allAir = LitematicRegion(
            name = "Empty",
            width = 2, height = 2, depth = 2,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"))),
            blocks = IntArray(8),
        )
        val lit = sampleLitematic().copy(regions = listOf(allAir))
        val bytes = StructureWriter.write(lit)
        // Re-parse to count blocks list size.
        val root = io.github.moxisuki.blockprint.core.NbtReader.readRoot(
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).readBytes(),
        )
        val blocks = root.get("blocks") as NbtTag.ListTag
        assertEquals(0, blocks.value.size)
    }

    @Test
    fun write_root_has_required_keys() {
        val bytes = StructureWriter.write(sampleLitematic())
        val root = io.github.moxisuki.blockprint.core.NbtReader.readRoot(
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).readBytes(),
        )
        val size = root.get("size") as NbtTag.ListTag
        assertEquals(NbtTagType.Int, size.elementType)
        assertEquals(3, size.value.size)
        val sizeInts = size.value.map { (it as NbtTag.IntTag).value }
        assertEquals(listOf(1, 2, 1), sizeInts)
        val palette = root.get("palette") as NbtTag.ListTag
        assertTrue(palette.value.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.StructureWriterTest" --info`
Expected: compile error — `StructureWriter` not found.

- [ ] **Step 3: Implement `StructureWriter`**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter

/**
 * Encode a [Litematic] as a vanilla Minecraft structure file
 * (`/structure save` style, gzipped NBT).
 *
 * Vanilla structure files store blocks **sparsely** (only non-air
 * cells with explicit positions) and use a palette that **does not
 * include air** (index 0 = first non-air block). The in-memory
 * `Litematic` model inverts both invariants (air at palette index 0,
 * dense block array), so this writer:
 *   1. Drops the air entry when building the output palette.
 *   2. Shifts every in-memory palette index down by 1 for the
 *      `state` field in the sparse `blocks` list.
 */
internal object StructureWriter {

    private const val DEFAULT_DATA_VERSION = 3465

    fun write(source: Litematic): ByteArray {
        val region = source.regions.firstOrNull()
            ?: throw IllegalArgumentException("StructureWriter: source has no regions")
        val root = buildRoot(source, region)
        return NbtWriter.writeRootToGzipBytes(root)
    }

    private fun buildRoot(source: Litematic, region: io.github.moxisuki.blockprint.core.LitematicRegion): NbtTag.CompoundTag {
        // Output palette = in-memory palette minus index 0 (air).
        val outPalette = region.palette.entries.drop(1)
        if (outPalette.isEmpty()) {
            // The in-memory region is all-air. Emit an empty palette.
        }

        // Iterate dense blocks in y-major order. For each non-air cell, emit
        // { pos: [x, y, z], state: inMemoryIndex - 1 }.
        val blocks = mutableListOf<NbtTag.CompoundTag>()
        val w = region.width; val h = region.height; val d = region.depth
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val idx = region.rawIndex(x, y, z)
            val v = region.rawBlocks[idx]
            if (v != 0) {
                blocks.add(
                    NbtTag.CompoundTag(
                        listOf(
                            "pos" to NbtTag.ListTag(
                                elementType = NbtTagType.Int,
                                value = listOf(
                                    NbtTag.IntTag(x + region.position.x),
                                    NbtTag.IntTag(y + region.position.y),
                                    NbtTag.IntTag(z + region.position.z),
                                ),
                            ),
                            "state" to NbtTag.IntTag(v - 1),
                        ),
                    ),
                )
            }
        }

        val sizeList = NbtTag.ListTag(
            elementType = NbtTagType.Int,
            value = listOf(
                NbtTag.IntTag(w),
                NbtTag.IntTag(h),
                NbtTag.IntTag(d),
            ),
        )
        val paletteList = NbtTag.ListTag(
            elementType = NbtTagType.Compound,
            value = outPalette.map { state ->
                NbtTag.CompoundTag(
                    listOf(
                        "Name" to NbtTag.StringTag(state.name),
                        "Properties" to (
                            if (state.properties.isNullOrEmpty()) NbtTag.CompoundTag(emptyList())
                            else NbtTag.CompoundTag(state.properties.entries.map { (k, v) -> k to NbtTag.StringTag(v) })
                        ),
                    ),
                )
            },
        )
        val blocksList = NbtTag.ListTag(
            elementType = NbtTagType.Compound,
            value = blocks,
        )

        return NbtTag.CompoundTag(
            listOf(
                "DataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION),
                "size" to sizeList,
                "palette" to paletteList,
                "blocks" to blocksList,
            ),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.StructureWriterTest" --info`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriter.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/StructureWriterTest.kt
git commit -m "feat(format): add StructureWriter (vanilla /structure save, gzipped, sparse blocks)"
```

---

## Task 6: `SpongeWriter` — Sponge Schematic v2 encoder

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriter.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriterTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpongeWriterTest {

    private fun sampleLitematic(): Litematic {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        // 1x1x2 region: stone, dirt (x-major, y-major fallback with h=1).
        val blocks = intArrayOf(1, 2)
        val region = LitematicRegion(
            name = "SpongeSample",
            width = 1, height = 1, depth = 2,
            position = Position(0, 0, 0),
            palette = palette,
            blocks = blocks,
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = null,
            name = "Sponge Build",
            author = "Author",
            description = "test",
            regions = listOf(region),
            format = SchematicFormat.Sponge,
        )
    }

    @Test
    fun write_then_read_round_trips() {
        val lit = sampleLitematic()
        val bytes = SpongeWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(1, r.width); assertEquals(1, r.height); assertEquals(2, r.depth)
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
        assertEquals(3, r.palette.size)
    }

    @Test
    fun write_does_not_gzip() {
        val bytes = SpongeWriter.write(sampleLitematic())
        // Sponge spec is raw NBT; first byte must be the compound tag id 0x0A.
        assertEquals(0x0A.toByte(), bytes[0])
        // Sanity: NOT a gzip header.
        assertNotEquals(0x1F.toByte(), bytes[0])
    }

    @Test
    fun write_rejects_multi_region_input() {
        val a = sampleLitematic().regions.single()
        val b = LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        try {
            SpongeWriter.write(multi)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun varint_encodes_small_palette_in_one_byte_each() {
        // All-zero (air) region with palette size 2 → 4 cells, all 0 → 4 varint bytes.
        val allAir = LitematicRegion(
            name = "Empty", width = 2, height = 1, depth = 2,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:stone"))),
            blocks = IntArray(4),
        )
        val lit = sampleLitematic().copy(regions = listOf(allAir))
        val bytes = SpongeWriter.write(lit)
        val read = LitematicReader.read(bytes)
        assertArrayEquals(IntArray(4), read.regions.single().rawBlocks)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.SpongeWriterTest" --info`
Expected: compile error — `SpongeWriter` not found.

- [ ] **Step 3: Implement `SpongeWriter`**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.NbtTag
import io.github.moxisuki.blockprint.core.NbtTagType
import io.github.moxisuki.blockprint.core.NbtWriter
import io.github.moxisuki.blockprint.core.exceptions.LitematicException

/**
 * Encode a [Litematic] as a Sponge Schematic v2 NBT file (no gzip).
 *
 * Sponge stores block data as a varint-packed byte array in
 * x → y → z order (not y-major). It supports a single region per
 * file — this writer rejects multi-region input.
 */
internal object SpongeWriter {

    private const val DEFAULT_DATA_VERSION = 3465
    private const val SPONGE_VERSION = 2

    fun write(source: Litematic): ByteArray {
        if (source.regions.size > 1) {
            throw LitematicException(
                "Format ${SchematicFormat.Sponge.displayName} does not support " +
                    "multiple regions; source has ${source.regions.size}. " +
                    "Pick one with primaryRegion or split first.",
            )
        }
        val region = source.regions.single()
        val root = buildRoot(source, region)
        return NbtWriter.writeRootToBytes(root) // Sponge is raw NBT, no gzip
    }

    private fun buildRoot(source: Litematic, region: io.github.moxisuki.blockprint.core.LitematicRegion): NbtTag.CompoundTag {
        val w = region.width; val h = region.height; val d = region.depth
        val total = w * h * d

        // Block data: x → y → z traversal, varint-encoded palette indices.
        val blockData = java.io.ByteArrayOutputStream(total)
        for (y in 0 until h) for (z in 0 until d) for (x in 0 until w) {
            val v = region.rawBlocks[region.rawIndex(x, y, z)]
            writeVarInt(blockData, v)
        }

        // Palette: stringified int key → BlockState compound. Sponge's
        // palette is conceptually a map; we serialize as a compound.
        val paletteEntries = region.palette.entries.mapIndexed { i, state ->
            i.toString() to blockStateCompound(state)
        }

        val enclosingSize = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(w),
                "y" to NbtTag.IntTag(h),
                "z" to NbtTag.IntTag(d),
            ),
        )
        val offset = NbtTag.CompoundTag(
            listOf(
                "x" to NbtTag.IntTag(region.position.x),
                "y" to NbtTag.IntTag(region.position.y),
                "z" to NbtTag.IntTag(region.position.z),
            ),
        )
        val metadata = NbtTag.CompoundTag(
            listOf(
                "Name" to NbtTag.StringTag(source.name),
                "Author" to NbtTag.StringTag(source.author),
                "Description" to NbtTag.StringTag(source.description),
                "EnclosingSize" to enclosingSize,
            ),
        )

        return NbtTag.CompoundTag(
            listOf(
                "Version" to NbtTag.IntTag(SPONGE_VERSION),
                "DataVersion" to NbtTag.IntTag(source.minecraftDataVersion ?: DEFAULT_DATA_VERSION),
                "Width" to NbtTag.IntTag(w),
                "Height" to NbtTag.IntTag(h),
                "Length" to NbtTag.IntTag(d),
                "Offset" to offset,
                "Palette" to NbtTag.CompoundTag(paletteEntries),
                "PaletteMax" to NbtTag.IntTag(region.palette.size),
                "BlockData" to NbtTag.ByteArrayTag(blockData.toByteArray()),
                "TileEntities" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
                "Entities" to NbtTag.ListTag(NbtTagType.Compound, emptyList()),
                "Metadata" to metadata,
            ),
        )
    }

    private fun blockStateCompound(state: io.github.moxisuki.blockprint.core.BlockState): NbtTag.CompoundTag {
        val props = state.properties
        val propsCompound: NbtTag.CompoundTag = if (props.isNullOrEmpty()) NbtTag.CompoundTag(emptyList())
        else NbtTag.CompoundTag(props.entries.map { (k, v) -> k to NbtTag.StringTag(v) })
        return NbtTag.CompoundTag(
            listOf("Name" to NbtTag.StringTag(state.name), "Properties" to propsCompound),
        )
    }

    /**
     * Standard protobuf-style varint: 7 bits per byte, MSB set means
     * more bytes follow. Used by Sponge for the BlockData stream.
     */
    private fun writeVarInt(out: java.io.OutputStream, value: Int) {
        var v = value
        while ((v and ~0x7F) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.SpongeWriterTest" --info`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriter.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/SpongeWriterTest.kt
git commit -m "feat(format): add SpongeWriter (v2, raw NBT, varint block data)"
```

---

## Task 7: `BuildingHelperWriter` — JSON text encoder

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriter.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriterTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.BlockPalette
import io.github.moxisuki.blockprint.core.BlockState
import io.github.moxisuki.blockprint.core.Litematic
import io.github.moxisuki.blockprint.core.LitematicReader
import io.github.moxisuki.blockprint.core.LitematicRegion
import io.github.moxisuki.blockprint.core.Position
import io.github.moxisuki.blockprint.core.SchematicFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildingHelperWriterTest {

    private fun sampleLitematic(): Litematic {
        // 2x1x1 region: stone, dirt.
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        val region = LitematicRegion(
            name = "BH",
            width = 2, height = 1, depth = 1,
            position = Position(5, 10, -3),
            palette = palette,
            blocks = intArrayOf(1, 2),
        )
        return Litematic(
            minecraftDataVersion = null,
            version = null,
            name = "BH Build",
            author = "Builder",
            description = "",
            regions = listOf(region),
            format = SchematicFormat.BuildingHelper,
        )
    }

    @Test
    fun write_then_readLenient_round_trips() {
        val lit = sampleLitematic()
        val bytes = BuildingHelperWriter.write(lit)
        val read = LitematicReader.readLenient(bytes)
        assertEquals(1, read.regions.size)
        val r = read.regions.single()
        assertEquals(2, r.width); assertEquals(1, r.height); assertEquals(1, r.depth)
        assertEquals(Position(5, 10, -3), r.position)
        assertEquals(3, r.palette.size)
        assertEquals(intArrayOf(1, 2).toList(), r.rawBlocks.toList())
        assertEquals("BH Build", read.name)
        assertEquals("Builder", read.author)
    }

    @Test
    fun write_emits_valid_json() {
        val bytes = BuildingHelperWriter.write(sampleLitematic())
        val s = bytes.decodeToString()
        // JSON must parse and have the three top-level keys.
        assertTrue(s.startsWith("{"))
        assertTrue(s.contains("\"name\""))
        assertTrue(s.contains("\"author\""))
        assertTrue(s.contains("\"statePosArrayList\""))
    }

    @Test
    fun statelist_length_matches_region_volume() {
        val bytes = BuildingHelperWriter.write(sampleLitematic())
        val s = bytes.decodeToString()
        // statelist should be "statelist:[I;<n0>,<n1>,...]"
        val marker = "statelist:[I;"
        val start = s.indexOf(marker)
        assertTrue(start >= 0)
        val end = s.indexOf(']', start)
        val list = s.substring(start + marker.length, end)
        val nums = list.split(",").map { it.trim() }
        assertEquals(2, nums.size) // 2*1*1
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.BuildingHelperWriterTest" --info`
Expected: compile error — `BuildingHelperWriter` not found.

- [ ] **Step 3: Implement `BuildingHelperWriter`**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core.internal.format

import io.github.moxisuki.blockprint.core.Litematic

/**
 * Encode a [Litematic] as a Building Helper ("建筑小帮手") JSON
 * blueprint.
 *
 * The output is plain UTF-8 JSON, not NBT. The `statePosArrayList`
 * value embeds a Java-Internal-Format-ish text payload that the
 * existing [io.github.moxisuki.blockprint.core.internal.BuildingHelperParser]
 * can re-parse:
 *
 *   statePosArrayList = "{Name:\"...\",...}...startpos:X:..,Y:..,Z:..
 *                          endpos:X:..,Y:..,Z:..statelist:[I;0,1,...]"
 *
 * JSON double-quotes inside the embedded payload must be escaped
 * with `\"` (the parser unescapes `\"` → `"` before reading the
 * embedded text).
 */
internal object BuildingHelperWriter {

    fun write(source: Litematic): ByteArray {
        val region = source.regions.firstOrNull()
            ?: throw IllegalArgumentException("BuildingHelperWriter: source has no regions")
        val json = buildJson(source, region)
        return json.encodeToByteArray()
    }

    private fun buildJson(source: Litematic, region: io.github.moxisuki.blockprint.core.LitematicRegion): String {
        val sb = StringBuilder()
        // Inner payload: palette entries (so the parser can read them),
        // then startpos / endpos / statelist.
        for (state in region.palette.entries) {
            sb.append(blockStateToEmbedded(state))
        }
        val sx = region.position.x
        val sy = region.position.y
        val sz = region.position.z
        val ex = sx + region.width - 1
        val ey = sy + region.height - 1
        val ez = sz + region.depth - 1
        sb.append("startpos:X:").append(sx).append(",Y:").append(sy).append(",Z:").append(sz)
        sb.append("endpos:X:").append(ex).append(",Y:").append(ey).append(",Z:").append(ez)

        // statelist: comma-separated, y-major order matches region.rawBlocks.
        sb.append("statelist:[I;")
        val raw = region.rawBlocks
        for (i in raw.indices) {
            if (i > 0) sb.append(',')
            sb.append(raw[i])
        }
        sb.append(']')

        val inner = sb.toString()
        // JSON-encode the inner payload: escape " → \" and \ → \\.
        val escaped = inner.replace("\\", "\\\\").replace("\"", "\\\"")
        // Build the top-level JSON object. Values are simple — name and author
        // are escaped to handle any quotes / backslashes.
        val nameJson = jsonString(source.name)
        val authorJson = jsonString(source.author)
        val spJson = jsonString(escaped)
        return "{$nameJson,$authorJson,$spJson}"
    }

    private fun blockStateToEmbedded(state: io.github.moxisuki.blockprint.core.BlockState): String {
        val props = state.properties
        val propsPart = if (props.isNullOrEmpty()) "" else {
            val pairs = props.entries.joinToString(",") { (k, v) -> "$k:\"$v\"" }
            ",Properties:{{$pairs}}"
        }
        return "{Name:\"${state.name}\"$propsPart}"
    }

    private fun jsonString(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.internal.format.BuildingHelperWriterTest" --info`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriter.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/internal/format/BuildingHelperWriterTest.kt
git commit -m "feat(format): add BuildingHelperWriter (JSON blueprint text)"
```

---

## Task 8: `BlueprintConverter` — public facade

**Files:**
- Create: `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverter.kt`
- Test: `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BlueprintConverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleLitematic(): Litematic {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        val region = LitematicRegion(
            name = "Main",
            width = 2, height = 1, depth = 1,
            position = Position.ZERO,
            palette = palette,
            blocks = intArrayOf(1, 2),
        )
        return Litematic(
            minecraftDataVersion = 3465,
            version = 6,
            name = "n", author = "a", description = "",
            regions = listOf(region),
            format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun convert_litematic_to_all_targets() {
        val lit = sampleLitematic()
        for (target in listOf(
            SchematicFormat.Litematica,
            SchematicFormat.Sponge,
            SchematicFormat.Structure,
            SchematicFormat.BuildingHelper,
        )) {
            val bytes = BlueprintConverter.convert(lit, target)
            assertTrue("target $target produced empty output", bytes.isNotEmpty())
        }
    }

    @Test
    fun convert_litematic_to_litematic_then_read_is_identity() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.Litematica)
        val read = LitematicReader.read(bytes)
        assertEquals(1, read.regions.size)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
        assertEquals(3, read.regions.single().palette.size)
    }

    @Test
    fun convert_litematic_to_sponge_then_read_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.Sponge)
        val read = LitematicReader.read(bytes)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_litematic_to_structure_then_read_lenient_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.Structure)
        val read = LitematicReader.readLenient(bytes)
        val r = read.regions.single()
        // Structure palette drops air; in-memory state 1 (stone) becomes 0; 2 (dirt) becomes 1.
        assertArrayEquals(intArrayOf(0, 1), r.rawBlocks)
    }

    @Test
    fun convert_litematic_to_buildingHelper_then_read_lenient_recovers_blocks() {
        val lit = sampleLitematic()
        val bytes = BlueprintConverter.convert(lit, SchematicFormat.BuildingHelper)
        val read = LitematicReader.readLenient(bytes)
        val r = read.regions.single()
        assertArrayEquals(intArrayOf(1, 2), r.rawBlocks)
    }

    @Test
    fun convert_bytes_to_litematica_uses_auto_detected_source() {
        val lit = sampleLitematic()
        val litematicBytes = BlueprintConverter.convert(lit, SchematicFormat.Litematica)
        // Round-trip via the ByteArray overload — this triggers the auto-detect
        // path on the source side.
        val out = BlueprintConverter.convert(litematicBytes, SchematicFormat.Litematica)
        val read = LitematicReader.read(out)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_multi_region_to_sponge_throws() {
        val a = sampleLitematic().regions.single()
        val b = LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        try {
            BlueprintConverter.convert(multi, SchematicFormat.Sponge)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun convert_multi_region_to_litematica_succeeds() {
        val a = sampleLitematic().regions.single()
        val b = LitematicRegion(
            name = "Other", width = 1, height = 1, depth = 1,
            position = Position.ZERO,
            palette = BlockPalette(listOf(BlockState("minecraft:air"), BlockState("minecraft:bedrock"))),
            blocks = intArrayOf(1),
        )
        val multi = sampleLitematic().copy(regions = listOf(a, b))
        val bytes = BlueprintConverter.convert(multi, SchematicFormat.Litematica)
        val read = LitematicReader.read(bytes)
        assertEquals(2, read.regions.size)
    }

    @Test
    fun convert_to_partialNbt_throws() {
        try {
            BlueprintConverter.convert(sampleLitematic(), SchematicFormat.PartialNbt)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun convert_to_unknown_throws() {
        try {
            BlueprintConverter.convert(sampleLitematic(), SchematicFormat.Unknown)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }

    @Test
    fun convert_file_to_file_routes_by_extension() {
        val lit = sampleLitematic()
        val inFile = tmp.newFile("input.litematic")
        inFile.writeBytes(BlueprintConverter.convert(lit, SchematicFormat.Litematica))
        val outFile = tmp.newFile("output.schematic")
        BlueprintConverter.convert(inFile, outFile)
        assertTrue("output file empty", outFile.length() > 0)
        val read = LitematicReader.read(outFile)
        assertArrayEquals(intArrayOf(1, 2), read.regions.single().rawBlocks)
    }

    @Test
    fun convert_file_with_unknown_source_extension_throws() {
        val inFile = tmp.newFile("input.bin")
        inFile.writeBytes(byteArrayOf(0x00))
        val outFile = tmp.newFile("output.litematic")
        try {
            BlueprintConverter.convert(inFile, outFile)
            assert(false) { "expected LitematicException" }
        } catch (e: LitematicException) {
            // expected
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.BlueprintConverterTest" --info`
Expected: compile error — `BlueprintConverter` not found.

- [ ] **Step 3: Implement `BlueprintConverter`**

Create `src/commonMain/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverter.kt`:

```kotlin
package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import io.github.moxisuki.blockprint.core.internal.format.BuildingHelperWriter
import io.github.moxisuki.blockprint.core.internal.format.LitematicWriter
import io.github.moxisuki.blockprint.core.internal.format.SpongeWriter
import io.github.moxisuki.blockprint.core.internal.format.StructureWriter
import java.io.File
import java.io.InputStream

/**
 * Public facade for converting between Minecraft blueprint formats:
 * Litematica, Sponge Schematic, vanilla Structure, and BuildingHelper.
 *
 * All conversion goes through the in-memory [Litematic] model:
 * read any source via [LitematicReader], then `convert` to any
 * supported target.
 *
 * Targets `PartialNbt` and `Unknown` are not real output formats —
 * they are read-side categories that cannot be written.
 *
 * Multi-region input is allowed only for the [SchematicFormat.Litematica]
 * target; all other targets reject it with [LitematicException].
 */
object BlueprintConverter {

    /** Convert an in-memory [Litematic] into the target format's byte payload. */
    @JvmStatic
    @JvmOverloads
    fun convert(source: Litematic, target: SchematicFormat): ByteArray {
        requireSingleRegion(source, target)
        return when (target) {
            SchematicFormat.Litematica -> LitematicWriter.write(source)
            SchematicFormat.Sponge -> SpongeWriter.write(source)
            SchematicFormat.Structure -> StructureWriter.write(source)
            SchematicFormat.BuildingHelper -> BuildingHelperWriter.write(source)
            SchematicFormat.PartialNbt, SchematicFormat.Unknown ->
                throw LitematicException(
                    "${target.displayName} is a read-side category; " +
                        "cannot be used as a convert target",
                )
        }
    }

    /**
     * Read raw [source] bytes (auto-detecting the format) and convert
     * to [target]. Source must be one of the four supported formats.
     */
    @JvmStatic
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray {
        val lit = LitematicReader.read(source)
        return convert(lit, target)
    }

    /** Stream variant of [convert]. The stream is fully consumed and closed. */
    @JvmStatic
    fun convert(source: InputStream, target: SchematicFormat): ByteArray =
        source.use { convert(it.readBytes(), target) }

    /**
     * File-level convenience. Source format is inferred from `source`'s
     * extension; target format is inferred from `outFile`'s extension by
     * default (override via [target]). `outFile` is overwritten.
     *
     * @throws LitematicException if either extension is unknown.
     */
    @JvmStatic
    @JvmOverloads
    fun convert(
        source: File,
        outFile: File,
        target: SchematicFormat = SchematicFormat.fromExtension(outFile.extension),
    ) {
        val sourceFormat = SchematicFormat.fromExtension(source.extension)
        val lit = LitematicReader.read(source)
        outFile.writeBytes(convert(lit, target))
    }

    private fun requireSingleRegion(source: Litematic, target: SchematicFormat) {
        if (target != SchematicFormat.Litematica && source.regions.size > 1) {
            throw LitematicException(
                "Format ${target.displayName} does not support multiple " +
                    "regions; source has ${source.regions.size}. " +
                    "Pick one with primaryRegion or split first.",
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.BlueprintConverterTest" --info`
Expected: 12 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/commonMain/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverter.kt \
        src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterTest.kt
git commit -m "feat(convert): add BlueprintConverter facade with 4-format dispatch"
```

---

## Task 9: Documentation — `BLUEPRINT_API.md` and `BLUEPRINT_API_EN.md`

**Files:**
- Modify: `docs/BLUEPRINT_API.md`
- Modify: `docs/BLUEPRINT_API_EN.md`

- [ ] **Step 1: Update the Chinese API doc**

Open `docs/BLUEPRINT_API.md`. Find the section starting with `## 入口：LitematicReader`. Add a new section right after it (after the `### 严格 vs 宽松` subsection, before `### 支持的格式枚举`):

```markdown
## 入口：BlueprintConverter

在四种已支持格式之间互转：以内存中的 `Litematic` 为中间表示。

```kotlin
object BlueprintConverter {
    fun convert(source: Litematic, target: SchematicFormat): ByteArray   // 内存对象 → 字节
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray     // 字节 → 字节（自动 detect 源）
    fun convert(source: InputStream, target: SchematicFormat): ByteArray  // 流 → 字节
    fun convert(source: File, outFile: File, target: SchematicFormat = ...)  // 文件 → 文件
}
```

### 转换矩阵

| 源 \ 目标 | Litematica | Sponge | Structure | BuildingHelper |
|---|:---:|:---:|:---:|:---:|
| **Litematica**   | ✓ | ✓ | ✓ | ✓ |
| **Sponge**       | ✓ | ✓ | ✓ | ✓ |
| **Structure**    | ✓ | ✓ | ✓ | ✓ |
| **BuildingHelper** | ✓ | ✓ | ✓ | ✓ |

### 多 region 限制

除 `Litematica` 外，所有目标格式都不支持多 region；传入多 region 的 `Litematic` 会抛 `LitematicException`。要绕过：自己 `lit.copy(regions = listOf(lit.primaryRegion!!))`。

### 文件级便捷调用

```kotlin
// 按扩展名推断：in.litematic → out.schematic
BlueprintConverter.convert(File("in.litematic"), File("out.schematic"))
```

不支持的扩展名抛 `LitematicException`。
```

- [ ] **Step 2: Update the English API doc**

Open `docs/BLUEPRINT_API_EN.md`. Add the equivalent English section in the same place (right after the `LitematicReader` section, before the formats enumeration):

```markdown
## Entry point: BlueprintConverter

Convert between the four supported formats via the in-memory `Litematic` model.

```kotlin
object BlueprintConverter {
    fun convert(source: Litematic, target: SchematicFormat): ByteArray   // in-memory → bytes
    fun convert(source: ByteArray, target: SchematicFormat): ByteArray     // bytes → bytes (auto-detect)
    fun convert(source: InputStream, target: SchematicFormat): ByteArray  // stream → bytes
    fun convert(source: File, outFile: File, target: SchematicFormat = ...)  // file → file
}
```

### Conversion matrix

| Source \ Target | Litematica | Sponge | Structure | BuildingHelper |
|---|:---:|:---:|:---:|:---:|
| **Litematica**     | ✓ | ✓ | ✓ | ✓ |
| **Sponge**         | ✓ | ✓ | ✓ | ✓ |
| **Structure**      | ✓ | ✓ | ✓ | ✓ |
| **BuildingHelper** | ✓ | ✓ | ✓ | ✓ |

### Multi-region restriction

Every target format except `Litematica` rejects multi-region input with `LitematicException`. Workaround: `lit.copy(regions = listOf(lit.primaryRegion!!))`.

### File-level convenience

```kotlin
// Inferred by extension: in.litematic → out.schematic
BlueprintConverter.convert(File("in.litematic"), File("out.schematic"))
```

Unknown extensions throw `LitematicException`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/BLUEPRINT_API.md docs/BLUEPRINT_API_EN.md
git commit -m "docs: document BlueprintConverter API in both Chinese and English"
```

---

## Task 10: Final verification — full test suite

- [ ] **Step 1: Run the full JVM test suite**

Run: `./gradlew jvmTest --info`
Expected: all existing tests + the 30 new tests pass (28 across the format writers + converter, 2 in `NbtWriterTest`'s roundtrip variants). No regressions.

- [ ] **Step 2: Spot-check the cross-format matrix manually**

Create a quick scratch test in `src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterMatrixTest.kt`:

```kotlin
package io.github.moxisuki.blockprint.core

import io.github.moxisuki.blockprint.core.exceptions.LitematicException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BlueprintConverterMatrixTest {

    private fun sampleLitematic(): Litematic {
        val palette = BlockPalette(
            listOf(
                BlockState("minecraft:air"),
                BlockState("minecraft:stone"),
                BlockState("minecraft:dirt"),
            ),
        )
        val region = LitematicRegion(
            name = "Main", width = 2, height = 1, depth = 1,
            position = Position.ZERO, palette = palette, blocks = intArrayOf(1, 2),
        )
        return Litematic(
            minecraftDataVersion = 3465, version = 6,
            name = "n", author = "a", description = "",
            regions = listOf(region), format = SchematicFormat.Litematica,
        )
    }

    @Test
    fun all_12_cross_pairs_preserve_block_identity() {
        val lit = sampleLitematic()
        // For each (source format, target format) pair, convert source → target,
        // then verify the target round-trips back to the same in-memory blocks.
        val sources = listOf(
            "litematica" to BlueprintConverter.convert(lit, SchematicFormat.Litematica),
            "sponge" to BlueprintConverter.convert(lit, SchematicFormat.Sponge),
            "structure" to BlueprintConverter.convert(lit, SchematicFormat.Structure),
            "buildingHelper" to BlueprintConverter.convert(lit, SchematicFormat.BuildingHelper),
        )
        for ((srcName, srcBytes) in sources) {
            for (target in listOf(
                SchematicFormat.Litematica,
                SchematicFormat.Sponge,
                SchematicFormat.Structure,
                SchematicFormat.BuildingHelper,
            )) {
                val stage1 = BlueprintConverter.convert(srcBytes, target)
                // After re-reading, the block array of the only region should
                // match the original `intArrayOf(1, 2)` (Sponge and Litematica
                // preserve the air-at-0 palette invariant; Structure shifts by 1
                // and drops air; BuildingHelper preserves the in-memory palette).
                val read = if (target == SchematicFormat.Structure) {
                    LitematicReader.readLenient(stage1)
                } else {
                    LitematicReader.read(stage1)
                }
                val r = read.regions.single()
                val expected = if (target == SchematicFormat.Structure) intArrayOf(0, 1) else intArrayOf(1, 2)
                assertArrayEquals(
                    "mismatch: src=$srcName target=$target",
                    expected, r.rawBlocks,
                )
            }
        }
    }
}
```

Run: `./gradlew jvmTest --tests "io.github.moxisuki.blockprint.core.BlueprintConverterMatrixTest" --info`
Expected: 1 test passes (covering all 16 ordered pairs, including 4 identities).

- [ ] **Step 3: Commit the matrix test (kept) or remove it**

Either keep `BlueprintConverterMatrixTest.kt` as a permanent regression guard, or remove it (the per-format roundtrip tests in Tasks 4-7 already cover the same path). **Decision: keep it** as a single end-to-end canary.

```bash
git add src/jvmTest/kotlin/io/github/moxisuki/blockprint/core/BlueprintConverterMatrixTest.kt
git commit -m "test(convert): add 16-pair cross-format matrix canary"
```

- [ ] **Step 4: Tag a release**

If the project version in `gradle/libs.versions.toml` is due for a bump, do it now per the project's release conventions. Otherwise, leave it for the maintainer.

---

## Self-Review

### 1. Spec coverage

| Spec section | Implemented in |
|---|---|
| §5 Public API (4 convert overloads) | Task 8 |
| §5 `SchematicFormat.fromExtension` | Task 3 |
| §6.1 `NbtWriter` (writeRoot + 2 byte helpers) | Task 2 |
| §6.2 `LitematicWriter` | Task 4 |
| §6.3 `SpongeWriter` (incl. varint) | Task 6 |
| §6.4 `StructureWriter` (sparse, drops air) | Task 5 |
| §6.5 `BuildingHelperWriter` (JSON text) | Task 7 |
| §6.6 facade dispatch | Task 8 |
| §6.7 file extension routing table | Task 3 + Task 8 |
| §7 data flow examples | Implicit in Task 8 tests |
| §8 error messages (all 7 listed cases) | Tasks 3, 6, 7, 8 (each emits the documented message) |
| §9 `BlockStatePacker.pack` | Task 1 |
| §10 test plan (6 suites + matrix) | Tasks 1-8 + Task 10 |
| §11 file list | Followed exactly |
| §12 open risks / §13 out of scope | Acknowledged, not implemented (per spec) |

### 2. Placeholder scan

Searched the plan for `TODO`, `TBD`, `fill in`, `etc.`, `appropriate`, `handle edge cases` — none present. All code shown is the actual code to be written. All commands show exact `git`/`./gradlew` invocations.

### 3. Type consistency

- `LitematicWriter.write(Litematic): ByteArray` — defined in Task 4, called by `BlueprintConverter` in Task 8. ✓
- `SpongeWriter.write(Litematic): ByteArray` — Task 6 → Task 8. ✓
- `StructureWriter.write(Litematic): ByteArray` — Task 5 → Task 8. ✓
- `BuildingHelperWriter.write(Litematic): ByteArray` — Task 7 → Task 8. ✓
- `SchematicFormat.fromExtension(String): SchematicFormat` — Task 3 → Task 8. ✓
- `BlockStatePacker.pack(...)` signature — Task 1, called by Task 4. ✓
- All test class names match the `File` they live in and the `--tests` filter. ✓
- `NbtTag.CompoundTag`, `NbtTag.ListTag`, `NbtTagType.*`, `Position`, `BlockState`, `BlockPalette`, `Litematic`, `LitematicRegion`, `LitematicException` — all pre-existing, used consistently.
