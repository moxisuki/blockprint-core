package io.github.moxisuki.blockprint.core.glb.mesh

import io.github.moxisuki.blockprint.core.glb.model.ElementRotation
import io.github.moxisuki.blockprint.core.glb.model.Face
import io.github.moxisuki.blockprint.core.glb.texture.AtlasEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-1 helper-equivalence tests.
 *
 * Each new `*Into` overload must produce output byte-equal to the legacy
 * helper. These tests lock in the invariant BEFORE PR-2 / PR-3 / PR-4 wire
 * the new helpers into the hot path; if any helper drifts the failure shows
 * up immediately and points at the offending function.
 *
 * Helpers being tested are `internal` on MeshBuilder; they were originally
 * `private` and were relaxed one notch so the jvmTest source set can reach
 * them. The relaxation is a no-op for end-user behaviour.
 */
class MeshBuilderHelpersParityTest {

    // ─────────────────────────────────────────────────────────────────────
    // Helpers used to convert the legacy "List<DoubleArray>" return shape
    // back into a flat DoubleArray so the two implementations can be
    // compared element-by-element.
    // ─────────────────────────────────────────────────────────────────────

    private fun List<DoubleArray>.toFlatDoubleArray(): DoubleArray {
        val out = DoubleArray(this.size * 3)
        for (i in this.indices) {
            val a = this[i]
            out[i * 3] = a[0]; out[i * 3 + 1] = a[1]; out[i * 3 + 2] = a[2]
        }
        return out
    }

    private fun assertDoubleArrayEq(
        tag: String, expected: DoubleArray, actual: DoubleArray, eps: Double = 0.0,
    ) {
        assertEquals("$tag length mismatch", expected.size, actual.size)
        for (i in expected.indices) {
            if (eps == 0.0) {
                assertEquals("$tag[$i] mismatch", expected[i], actual[i], 0.0)
            } else {
                assertEquals("$tag[$i] mismatch (eps=$eps)", expected[i], actual[i], eps)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // facePlaneCornersInto vs facePlaneCorners
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun facePlaneCornersInto_matches_legacy_for_all_six_directions() {
        val from = listOf(0.0, 0.0, 0.0)
        val to = listOf(16.0, 16.0, 16.0)
        for (dir in listOf("up", "down", "north", "south", "east", "west")) {
            val legacy = MeshBuilder.facePlaneCorners(dir, from, to).toFlatDoubleArray()
            val actual = DoubleArray(12)
            MeshBuilder.facePlaneCornersInto(actual, 0, dir, from, to)
            assertDoubleArrayEq("facePlaneCorners[$dir]", legacy, actual)
        }
    }

    @Test
    fun facePlaneCornersInto_matches_legacy_for_non_cubic_box() {
        // 14x8x14 "cake" style box to exercise the non-axis-aligned cases.
        val from = listOf(1.0, 4.0, 1.0)
        val to = listOf(15.0, 12.0, 15.0)
        for (dir in listOf("up", "down", "north", "south", "east", "west")) {
            val legacy = MeshBuilder.facePlaneCorners(dir, from, to).toFlatDoubleArray()
            val actual = DoubleArray(12)
            MeshBuilder.facePlaneCornersInto(actual, 0, dir, from, to)
            assertDoubleArrayEq("non-cubic[$dir]", legacy, actual)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // rotateElementPointInto vs rotateElementPoint
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun rotateElementPointInto_matches_legacy_rescale_true() {
        val rot = ElementRotation(origin = listOf(8.0, 8.0, 8.0), axis = "y", angle = 45.0, rescale = true)
        val cases = listOf(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(16.0, 16.0, 16.0),
            doubleArrayOf(3.0, 7.5, 12.0),
        )
        for (p in cases) {
            val legacy = MeshBuilder.rotateElementPoint(p, rot)
            val actual = DoubleArray(3)
            MeshBuilder.rotateElementPointInto(actual, 0, p[0], p[1], p[2], rot)
            assertDoubleArrayEq("rotElemRescale(${p.contentToString()})", legacy, actual)
        }
    }

    @Test
    fun rotateElementPointInto_matches_legacy_rescale_false_each_axis() {
        for (axis in listOf("x", "y", "z")) {
            val rot = ElementRotation(origin = listOf(8.0, 8.0, 8.0), axis = axis, angle = 22.5, rescale = false)
            val p = doubleArrayOf(2.0, 5.0, 13.0)
            val legacy = MeshBuilder.rotateElementPoint(p, rot)
            val actual = DoubleArray(3)
            MeshBuilder.rotateElementPointInto(actual, 0, p[0], p[1], p[2], rot)
            assertDoubleArrayEq("rotElemNoRescale[$axis]", legacy, actual)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // rotatePointInto vs rotatePoint
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun rotatePointInto_matches_legacy_zero_rotation_is_copy() {
        val p = doubleArrayOf(3.0, 7.0, 9.0)
        val legacy = MeshBuilder.rotatePoint(p, 0, 0)
        val actual = DoubleArray(3)
        MeshBuilder.rotatePointInto(actual, 0, p[0], p[1], p[2], 0, 0)
        assertDoubleArrayEq("rotatePoint[0,0]", legacy, actual)
    }

    @Test
    fun rotatePointInto_matches_legacy_all_axes() {
        val p = doubleArrayOf(0.5, 12.0, 8.5)
        for ((rx, ry) in listOf(0 to 90, 90 to 0, 90 to 180, 270 to 90, 45 to 135)) {
            val legacy = MeshBuilder.rotatePoint(p, rx, ry)
            val actual = DoubleArray(3)
            MeshBuilder.rotatePointInto(actual, 0, p[0], p[1], p[2], rx, ry)
            assertDoubleArrayEq("rotatePoint[${rx},${ry}]", legacy, actual)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // faceNormalToDir(buf, off) vs faceNormalToDir(corners: List<DoubleArray>)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun faceNormalToDir_buf_form_matches_legacy_for_all_directions() {
        // Build planes that face each of the 6 dirs explicitly via facePlaneCorners.
        val from = listOf(0.0, 0.0, 0.0)
        val to = listOf(16.0, 16.0, 16.0)
        for (dir in listOf("up", "down", "north", "south", "east", "west")) {
            val corners = MeshBuilder.facePlaneCorners(dir, from, to)
            val buf = corners.toFlatDoubleArray()
            assertEquals(
                "faceNormalToDir[$dir] mismatch",
                MeshBuilder.faceNormalToDir(corners),
                MeshBuilder.faceNormalToDir(buf, 0),
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // isFaceOnBoundary(buf, off) vs isFaceOnBoundary(corners: List<DoubleArray>)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun isFaceOnBoundary_buf_form_matches_legacy() {
        val from = listOf(0.0, 0.0, 0.0)
        val to = listOf(16.0, 16.0, 16.0)
        for (dir in listOf("up", "down", "north", "south", "east", "west")) {
            val corners = MeshBuilder.facePlaneCorners(dir, from, to)
            val buf = corners.toFlatDoubleArray()
            assertEquals(
                "isFaceOnBoundary[$dir]",
                MeshBuilder.isFaceOnBoundary(dir, corners),
                MeshBuilder.isFaceOnBoundary(dir, buf, 0),
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getFaceUVInto vs getFaceUV
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun getFaceUVInto_matches_legacy_no_explicit_uv() {
        // Auto-UV path (face.uv == null).
        val face = Face(texture = "minecraft:block/stone", uv = null, cullface = null)
        val from = listOf(0.0, 0.0, 0.0)
        val to = listOf(16.0, 16.0, 16.0)
        for (dir in listOf("up", "down", "north", "south", "east", "west", "garbage")) {
            val legacy = MeshBuilder.getFaceUV(face, dir, from, to).toDoubleArray()
            val actual = DoubleArray(4)
            MeshBuilder.getFaceUVInto(actual, 0, face, dir, from, to)
            assertDoubleArrayEq("getFaceUV[$dir]", legacy, actual)
        }
    }

    @Test
    fun getFaceUVInto_matches_legacy_with_explicit_uv() {
        // When face.uv != null, legacy returns it as-is.
        val explicit = listOf(1.0, 2.0, 3.0, 4.0)
        val face = Face(texture = "minecraft:block/stone", uv = explicit, cullface = null)
        val legacy = MeshBuilder.getFaceUV(face, "north", listOf(0.0, 0.0, 0.0), listOf(16.0, 16.0, 16.0))
        val actual = DoubleArray(4)
        MeshBuilder.getFaceUVInto(actual, 0, face, "north", listOf(0.0, 0.0, 0.0), listOf(16.0, 16.0, 16.0))
        assertEquals("size mismatch", legacy.size, actual.size)
        for (i in actual.indices) assertEquals("getFaceUV explicit[$i]", legacy[i], actual[i], 0.0)
    }

    // ─────────────────────────────────────────────────────────────────────
    // computeUVsInto(outUVs) vs computeUVs(...)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun computeUVsInto_matches_legacy_for_all_directions_and_mirror_flags() {
        val entry = AtlasEntry(u1 = 0.125f, v1 = 0.25f, u2 = 0.375f, v2 = 0.5f)
        val uvList = listOf(2.0, 4.0, 10.0, 12.0)
        val uvArr = doubleArrayOf(2.0, 4.0, 10.0, 12.0)
        for (dir in listOf("up", "down", "north", "south", "east", "west")) {
            for (mirror in booleanArrayOf(false, true)) {
                val legacy = MeshBuilder.computeUVs(dir, uvList, entry, mirror = mirror, noVFlip = false)
                val outUVs = Array(4) { FloatArray(2) }
                MeshBuilder.computeUVsInto(dir, uvArr, 0, entry, mirror = mirror, noVFlip = false, outUVs = outUVs)
                assertEquals("$dir/mirror=$mirror: legacy size", 4, legacy.size)
                for (i in 0 until 4) {
                    assertEquals(
                        "$dir/mirror=$mirror uv[$i][0]",
                        legacy[i][0], outUVs[i][0], 1e-6f,
                    )
                    assertEquals(
                        "$dir/mirror=$mirror uv[$i][1]",
                        legacy[i][1], outUVs[i][1], 1e-6f,
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // applyFaceRotationInto(outUVs) vs applyFaceRotation(...)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun applyFaceRotationInto_matches_legacy_for_all_step_counts() {
        // Build a 4-UV list with distinct values so any rot drift shows.
        val base = listOf(
            floatArrayOf(1f, 10f),
            floatArrayOf(2f, 20f),
            floatArrayOf(3f, 30f),
            floatArrayOf(4f, 40f),
        )
        for (rotation in intArrayOf(0, 90, 180, 270, -90, 360)) {
            val legacy = MeshBuilder.applyFaceRotation(base, rotation)
            val buf = Array(4) { FloatArray(2) }
            for (i in 0 until 4) {
                buf[i][0] = base[i][0]; buf[i][1] = base[i][1]
            }
            MeshBuilder.applyFaceRotationInto(buf, rotation)
            assertEquals("rot=$rotation size", 4, legacy.size)
            for (i in 0 until 4) {
                assertEquals("rot=$rotation uv[$i][0]", legacy[i][0], buf[i][0], 1e-6f)
                assertEquals("rot=$rotation uv[$i][1]", legacy[i][1], buf[i][1], 1e-6f)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Touching FaceScratch (PR-1b skeleton) — must compile and have the
    // expected shape so PR-2 can rely on it.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun faceScratch_has_expected_buffer_shapes() {
        val s = MeshBuilder.newFaceScratch()
        // 4 corners × 3 doubles
        assertEquals(12, s.corners.size)
        assertEquals(12, s.elemRotated.size)
        assertEquals(12, s.rotated.size)
        assertEquals(12, s.finalRotated.size)
        // 4 UV slots, each a (u, v) float pair
        assertEquals(4, s.baseUVs.size)
        assertEquals(4, s.faceUVs.size)
        s.baseUVs.forEach { assertEquals(2, it.size) }
        s.faceUVs.forEach { assertEquals(2, it.size) }
        // 4 vertices × 3 floats
        assertEquals(12, s.verts.size)
        // getFaceUV output area
        assertEquals(4, s.uv.size)
        // normal vector
        assertEquals(3, s.normal.size)
        // processRawMeshInto per-vertex rotation scratch (3 doubles)
        assertEquals(3, s.tmpVec3.size)
    }

    @Test
    fun faceScratch_normal_starts_at_zero() {
        val s = MeshBuilder.newFaceScratch()
        // The shared face-normal scratch is overwritten on every face; ensure
        // it starts zeroed so a missed write is detectable.
        assertTrue(s.normal.all { it == 0f })
    }

    // ─────────────────────────────────────────────────────────────────────
    // dirToNormalArrayInto / rotateNormalInto (PR-3 additions).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun dirToNormalArrayInto_matches_legacy_for_all_six_directions() {
        val legacyByDir = mapOf(
            "up" to floatArrayOf(0f, 1f, 0f),
            "down" to floatArrayOf(0f, -1f, 0f),
            "north" to floatArrayOf(0f, 0f, -1f),
            "south" to floatArrayOf(0f, 0f, 1f),
            "east" to floatArrayOf(1f, 0f, 0f),
            "garbage" to floatArrayOf(-1f, 0f, 0f),
        )
        for ((dir, expected) in legacyByDir) {
            // Legacy helper returns a fresh FloatArray each call.
            val legacy = MeshBuilder.dirToNormalArray(dir)
            val buf = FloatArray(3)
            MeshBuilder.dirToNormalArrayInto(buf, dir)
            for (i in 0 until 3) {
                assertEquals("$dir[$i]", legacy[i], buf[i], 0f)
                assertEquals("$dir[$i] (expected)", expected[i], buf[i], 0f)
            }
        }
    }

    @Test
    fun rotateNormalInto_matches_legacy_zero_rotation_is_identity() {
        val n = doubleArrayOf(0.5, 12.0, -3.0)
        val legacy = MeshBuilder.rotateNormal(n, 0, 0)
        val buf = DoubleArray(3)
        MeshBuilder.rotateNormalInto(buf, 0, n[0], n[1], n[2], 0, 0)
        assertDoubleArrayEq("rotateNormal[0,0]", legacy, buf)
    }

    @Test
    fun rotateNormalInto_matches_legacy_all_axes() {
        val n = doubleArrayOf(0.5, 12.0, -3.0)
        for ((rx, ry) in listOf(0 to 90, 90 to 0, 90 to 180, 270 to 90, 45 to 135)) {
            val legacy = MeshBuilder.rotateNormal(n, rx, ry)
            val buf = DoubleArray(3)
            MeshBuilder.rotateNormalInto(buf, 0, n[0], n[1], n[2], rx, ry)
            assertDoubleArrayEq("rotateNormal[${rx},${ry}]", legacy, buf)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sanity: a List<DoubleArray> of 4 corners is structural identical to a
    // DoubleArray of 12 slots in expected writer order.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun sanity_toFlatDoubleArray_reorders_correctly() {
        val corners = listOf(
            doubleArrayOf(1.0, 2.0, 3.0),
            doubleArrayOf(4.0, 5.0, 6.0),
            doubleArrayOf(7.0, 8.0, 9.0),
            doubleArrayOf(10.0, 11.0, 12.0),
        )
        val flat = corners.toFlatDoubleArray()
        assertArrayEquals(
            doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0),
            flat, 0.0,
        )
    }
}
