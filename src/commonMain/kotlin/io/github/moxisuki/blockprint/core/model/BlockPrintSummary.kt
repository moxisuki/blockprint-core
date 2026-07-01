package io.github.moxisuki.blockprint.core.model

import io.github.moxisuki.blockprint.core.SchematicFormat

data class BlockPrintSummary(
    val format: SchematicFormat,
    val name: String,
    val author: String,
    val description: String,
    val version: Int?,
    val minecraftDataVersion: Int?,
)