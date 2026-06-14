package io.github.moxisuki.blockprint.core

/**
 * Integer 3D position. Used for region origins inside a litematic's world
 * coordinate space.
 */
data class Position(val x: Int, val y: Int, val z: Int) {
    companion object {
        val ZERO = Position(0, 0, 0)
    }
}
