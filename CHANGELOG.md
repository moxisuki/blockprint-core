# Changelog

## 1.0.0 (2026-07-02)

### Breaking changes
- `LitematicReader` → `BlockPrintReader` (now in `io.github.moxisuki.blockprint.core.api`)
- `Litematic` → `BlockPrintDocument` (now in `io.github.moxisuki.blockprint.core.model`)
- `LitematicRegion` → `BlockPrintRegion` (now in `io.github.moxisuki.blockprint.core.model`)
- `LitematicException` → `BlockPrintException` (now in `io.github.moxisuki.blockprint.core.exceptions`)
- `LitematicToGlb` → `BlockPrintToGlb` (now in `io.github.moxisuki.blockprint.core.api`)
- `BlueprintConverter` → `BlockPrintConverter`
- Package layout: `internal/format/` → `format/<formatName>/`; `internal/LitematicParser.kt` split into per-format readers

### New features
- `BlockPrintReader.peek(...)` returns `BlockPrintSummary` (header-only read, skips block data)

### Performance
- `NbtReader.readRoot(InputStream)` is now truly streaming (no longer materializes the full byte array)
- `PackedBlocks` specialized 4/8-bit unpacking (Litematica hot path)
- `NbtReader.readRootHeader` + `skipPayload` for Peek short-circuit (skips `Regions` / `Schematic` subtrees)

### Notes
- `BlockPrintToGlb` is fully wired: `convert(File/OutputStream, ...)` and `convertToBytes(...)` work end-to-end via the two-pass streaming pipeline (count → write header → stream mesh).
