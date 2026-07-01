package io.github.moxisuki.blockprint.core.glb.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshBuilderFloorSplittingTest {

    // computeFloorPlan tests

    @Test
    fun floorHeight_zero_meansSingleFloorSpanningRegion() {
        val plan = computeFloorPlan(regionHeight = 30, floorHeight = 0)
        assertEquals("effective height equals region height", 30, plan.effectiveFloorHeight)
        assertEquals(1, plan.floorCount)
    }

    @Test
    fun floorHeight_one_givesOneFloorPerVoxel() {
        val plan = computeFloorPlan(regionHeight = 30, floorHeight = 1)
        assertEquals(1, plan.effectiveFloorHeight)
        assertEquals(30, plan.floorCount)
    }

    @Test
    fun floorHeight_four_dividesCleanly() {
        val plan = computeFloorPlan(regionHeight = 32, floorHeight = 4)
        assertEquals(4, plan.effectiveFloorHeight)
        assertEquals(8, plan.floorCount)
    }

    @Test
    fun floorHeight_doesNotDivideCleanly_topFloorAbsorbsRemainder() {
        val plan = computeFloorPlan(regionHeight = 30, floorHeight = 4)
        assertEquals(4, plan.effectiveFloorHeight)
        assertEquals("ceil(30/4) = 8", 8, plan.floorCount)
    }

    @Test
    fun floorHeight_greaterThanRegion_clampsToOneFloor() {
        val plan = computeFloorPlan(regionHeight = 5, floorHeight = 100)
        assertEquals(1, plan.floorCount)
    }

    @Test
    fun floorHeight_negative_isCoercedToZero() {
        val plan = computeFloorPlan(regionHeight = 30, floorHeight = -3)
        assertEquals("negative coerced to 0 → 1 floor spanning region", 30, plan.effectiveFloorHeight)
        assertEquals(1, plan.floorCount)
    }

    // floorIndexForY tests

    @Test
    fun floorIndex_basicAssignment() {
        val plan = computeFloorPlan(regionHeight = 30, floorHeight = 4)
        assertEquals(0, floorIndexForY(0, plan))
        assertEquals(0, floorIndexForY(3, plan))
        assertEquals(1, floorIndexForY(4, plan))
        assertEquals(1, floorIndexForY(7, plan))
        assertEquals(2, floorIndexForY(8, plan))
        assertEquals("top floor absorbs remainder", 7, floorIndexForY(29, plan))
    }
}
