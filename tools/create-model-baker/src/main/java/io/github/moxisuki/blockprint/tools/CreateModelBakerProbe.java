package io.github.moxisuki.blockprint.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@net.neoforged.fml.common.Mod(CreateModelBakerProbe.MOD_ID)
public final class CreateModelBakerProbe {
    public static final String MOD_ID = "blockprint_model_baker";
    private static final List<String> atlasDiagnostics = new ArrayList<>();
    private static volatile PendingExport pendingExport;
    private static boolean exportRunning;

    public CreateModelBakerProbe(
        net.neoforged.bus.api.IEventBus modEventBus,
        net.neoforged.fml.ModContainer modContainer
    ) {
        System.out.println("[BlockPrintModelBaker] temporary NeoForge mod constructed: " + modContainer.getModId());
        modEventBus.addListener(CreateModelBakerProbe::onCommonSetup);
        modEventBus.addListener(CreateModelBakerProbe::onGatherData);
        modEventBus.addListener(CreateModelBakerProbe::onBakingCompleted);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(CreateModelBakerProbe::onClientTick);
    }

    public static void main(String[] args) {
        System.out.println("[BlockPrintModelBaker] java.awt.headless=" + System.getProperty("java.awt.headless"));
        System.out.println("[BlockPrintModelBaker] Minecraft=" + net.minecraft.client.Minecraft.class.getName());
        System.out.println("[BlockPrintModelBaker] ModelData=" + net.neoforged.neoforge.client.model.data.ModelData.class.getName());
        System.out.println("[BlockPrintModelBaker] CreatePartialModels=" + com.simibubi.create.AllPartialModels.class.getName());
        System.out.println("[BlockPrintModelBaker] OK: client model classpath is reachable.");

        if (bootstrapMinecraft()) {
            reportBlock("minecraft:stone");
            reportBlock("create:fluid_pipe");
            reportBlock("create:mechanical_arm");
        }

        System.out.println("[BlockPrintModelBaker] If Create blocks are absent here, plain JavaExec is not enough; the baker must run through NeoForge's mod-loading lifecycle.");
        System.out.println("[BlockPrintModelBaker] Next step after registry availability: initialize resource/model managers and call BakedModel#getQuads.");
    }

    private static void onCommonSetup(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        System.out.println("[BlockPrintModelBaker] FMLCommonSetupEvent: registry probe");
        reportBlock("minecraft:stone");
        reportBlock("create:fluid_pipe");
        reportBlock("create:mechanical_arm");
    }

    private static void onGatherData(net.neoforged.neoforge.data.event.GatherDataEvent event) {
        System.out.println("[BlockPrintModelBaker] GatherDataEvent: registry probe");
        reportBlock("minecraft:stone");
        reportBlock("create:fluid_pipe");
        reportBlock("create:mechanical_arm");
        if (Boolean.getBoolean("blockprint.baker.exitAfterProbe")) {
            System.out.println("[BlockPrintModelBaker] Probe complete; exiting data run.");
            System.out.flush();
            System.exit(0);
        }
    }

    private static void onBakingCompleted(net.neoforged.neoforge.client.event.ModelEvent.BakingCompleted event) {
        PendingExport export = new PendingExport(
            event.getModelManager(),
            new LinkedHashMap<>(event.getModels())
        );
        if (Boolean.parseBoolean(System.getProperty("blockprint.baker.deferUntilClientTick", "true"))) {
            pendingExport = export;
            System.out.println(
                "[BlockPrintModelBaker] ModelEvent.BakingCompleted: saved " + export.models().size() +
                " baked models; renderer recording will start after client resource reload"
            );
            return;
        }
        exportModels(export);
    }

    private static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        PendingExport export = pendingExport;
        if (export == null || exportRunning) {
            return;
        }
        pendingExport = null;
        exportModels(export);
    }

    private static void exportModels(PendingExport export) {
        exportRunning = true;
        System.out.println("[BlockPrintModelBaker] Client ready: exporting recorded static models");
        loadDefaultServerConfigs();
        try {
            atlasDiagnostics.clear();
            ExportSelection selection = configuredSelection();
            List<String> blocks = selection.blockIds();
            System.out.println("[BlockPrintModelBaker] Selection source: " + selection.sourceDescription());
            System.out.println("[BlockPrintModelBaker] Selected blocks=" + blocks.size() + ", constrainedStates=" + selection.constrainedStateCount());
            Path outputDir = Path.of(System.getProperty("blockprint.baker.outputDir", "build/blockprint-model-baker/generated"));
            Files.createDirectories(outputDir);
            Files.deleteIfExists(outputDir.resolve("capture-error.txt"));

            if (blocks.size() <= 16 || Boolean.getBoolean("blockprint.baker.writeProbe")) {
                String json = exportBlocksJson(export.modelManager(), export.models(), selection);
                Path output = outputDir.resolve("probe-quads.json");
                Files.writeString(output, json, StandardCharsets.UTF_8);
                System.out.println("[BlockPrintModelBaker] Wrote baked quad probe to " + output.toAbsolutePath());
            } else {
                System.out.println("[BlockPrintModelBaker] Skipping probe-quads.json for " + blocks.size() + " blocks.");
            }

            Path splitDir = outputDir.resolve("blockprint").resolve("baked-models").resolve("by-block");
            int splitCount = exportSplitBakedModelManifests(export.modelManager(), export.models(), selection, splitDir);
            System.out.println("[BlockPrintModelBaker] Wrote " + splitCount + " split baked model manifests under " + splitDir.toAbsolutePath());

            Path manifestOutput = outputDir.resolve("blockprint").resolve("baked-models").resolve(manifestFileName());
            boolean writeCombined = Boolean.getBoolean("blockprint.baker.writeCombinedManifest") || blocks.size() <= 64;
            if (writeCombined) {
                String manifest = exportBakedModelManifest(export.modelManager(), export.models(), selection);
                Files.createDirectories(manifestOutput.getParent());
                Files.writeString(manifestOutput, manifest, StandardCharsets.UTF_8);
                System.out.println("[BlockPrintModelBaker] Wrote combined baked model manifest to " + manifestOutput.toAbsolutePath());
            } else {
                Files.deleteIfExists(manifestOutput);
                System.out.println("[BlockPrintModelBaker] Skipping combined manifest for " + blocks.size() + " blocks; split manifests are the runtime-friendly output.");
            }
            Files.writeString(
                outputDir.resolve("atlas-diagnostics.txt"),
                String.join(System.lineSeparator(), atlasDiagnostics),
                StandardCharsets.UTF_8
            );
            String provenanceDiagnostics = FlywheelTextureProvenance.diagnosticsSummary();
            Files.writeString(
                outputDir.resolve("flywheel-provenance-diagnostics.txt"),
                provenanceDiagnostics + System.lineSeparator(),
                StandardCharsets.UTF_8
            );
            System.out.println("[BlockPrintModelBaker] Flywheel texture provenance: " + provenanceDiagnostics);
            writeBlockAtlasLayout(outputDir.resolve("block-atlas-layout.tsv"));
            writeAtlasLayouts(outputDir.resolve("atlas-layouts.tsv"));
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Client model bake export failed: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            writeCaptureFailure(t);
        }
        if (Boolean.getBoolean("blockprint.baker.exitAfterBake")) {
            System.out.println("[BlockPrintModelBaker] Bake probe complete; stopping Minecraft client.");
            System.out.flush();
            net.minecraft.client.Minecraft.getInstance().stop();
        }
    }

    private static void loadDefaultServerConfigs() {
        if (!Boolean.parseBoolean(System.getProperty("blockprint.baker.loadDefaultServerConfigs", "true"))) {
            return;
        }
        try {
            net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
            System.out.println("[BlockPrintModelBaker] Loaded default SERVER configs for offline BlockEntity construction");
        } catch (Throwable failure) {
            System.out.println(
                "[BlockPrintModelBaker] Default SERVER config loading failed; affected BlockEntities will be skipped: " +
                failure.getClass().getName() + ": " + failure.getMessage()
            );
        }
    }

    private static void writeCaptureFailure(Throwable failure) {
        try {
            Path outputDir = Path.of(System.getProperty("blockprint.baker.outputDir", "build/blockprint-model-baker/generated"));
            Files.createDirectories(outputDir);
            java.io.StringWriter text = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(text));
            Files.writeString(outputDir.resolve("capture-error.txt"), text.toString(), StandardCharsets.UTF_8);
        } catch (Throwable reportFailure) {
            System.out.println(
                "[BlockPrintModelBaker] Failed to write capture-error.txt: " +
                reportFailure.getClass().getName() + ": " + reportFailure.getMessage()
            );
        }
    }

    private record PendingExport(
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models
    ) {}

    private static String exportBlocksJson(
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models,
        ExportSelection selection
    ) throws IOException {
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("{\n  \"schema\": \"blockprint.baked-quads.probe.v1\",\n  \"blocks\": [\n");
        List<String> ids = selection.blockIds();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            appendBlockJson(out, modelManager, models, ids.get(i), selection.stateKeysFor(ids.get(i)));
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static int exportSplitBakedModelManifests(
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models,
        ExportSelection selection,
        Path splitDir
    ) throws IOException {
        int count = 0;
        List<String> ids = selection.blockIds();
        for (String id : ids) {
            net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(id);
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(location)) {
                continue;
            }
            Path output = splitDir.resolve(location.getNamespace()).resolve(location.getPath() + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, exportBakedModelManifest(modelManager, models, selection.only(id)), StandardCharsets.UTF_8);
            count++;
        }
        return count;
    }

    private static String exportBakedModelManifest(
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models,
        ExportSelection selection
    ) {
        List<String> ids = selection.blockIds();
        StringBuilder out = new StringBuilder(Math.max(256 * 1024, ids.size() * 4096));
        out.append("{\n");
        appendJsonField(out, "schema", "blockprint.baked-models.v1", 2, true);
        appendJsonField(out, "source", "NeoForge client ModelEvent.BakingCompleted", 2, true);
        appendJsonField(out, "selection", selection.sourceDescription(), 2, true);
        out.append("  \"blocks\": [\n");
        boolean wroteBlock = false;
        for (String id : ids) {
            net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(id);
            if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(location)) {
                System.out.println("[BlockPrintModelBaker] Skipping missing block " + id);
                continue;
            }
            if (wroteBlock) {
                out.append(",\n");
            }
            appendManifestBlock(out, modelManager, models, id, selection.stateKeysFor(id));
            wroteBlock = true;
        }
        out.append("\n  ]\n");
        out.append("}\n");
        return out.toString();
    }

    private static void appendManifestBlock(
        StringBuilder out,
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models,
        String id,
        Set<String> allowedStateKeys
    ) {
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(id);
        net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(location);
        List<net.minecraft.world.level.block.state.BlockState> states = filterStates(block.getStateDefinition().getPossibleStates(), allowedStateKeys);
        if (allowedStateKeys != null && !allowedStateKeys.isEmpty() && states.isEmpty()) {
            System.out.println("[BlockPrintModelBaker] WARNING: no block states matched " + id + " selectors " + allowedStateKeys);
        }
        List<StateSample> samples = collectStateSamples(modelManager, models, states, true);

        out.append("    {\n");
        appendJsonField(out, "id", id, 6, true);
        out.append("      \"states\": [\n");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            appendManifestState(out, samples.get(i));
        }
        out.append("\n      ]\n");
        out.append("    }");
    }

    private static void appendManifestState(StringBuilder out, StateSample sample) {
        Map<String, MeshAccumulator> meshes = new LinkedHashMap<>();
        for (QuadSample quadSample : sample.quads) {
            net.minecraft.client.renderer.block.model.BakedQuad quad = quadSample.quad;
            String texture = texturePath(quad.getSprite().contents().name());
            MeshAccumulator mesh = meshes.computeIfAbsent(texture, MeshAccumulator::new);
            mesh.add(quad);
        }
        for (MeshAccumulator mesh : sample.rendererMeshes) {
            meshes.merge(mesh.texture, mesh, MeshAccumulator::merge);
        }

        out.append("        {\n");
        appendJsonField(out, "key", stateKey(sample.state), 10, true);
        appendJsonField(out, "modelClass", sample.model.getClass().getName(), 10, true);
        out.append("          \"meshes\": [\n");
        int i = 0;
        for (MeshAccumulator mesh : meshes.values()) {
            if (i > 0) {
                out.append(",\n");
            }
            appendManifestMesh(out, mesh);
            i++;
        }
        out.append("\n          ]\n");
        out.append("        }");
    }

    private static void appendManifestMesh(StringBuilder out, MeshAccumulator mesh) {
        out.append("            {\n");
        appendJsonField(out, "texture", mesh.texture, 14, true);
        appendFloatArray(out, "positions", mesh.positions, 14, true);
        appendFloatArray(out, "uvs", mesh.uvs, 14, true);
        appendFloatArray(out, "normals", mesh.normals, 14, true);
        appendIntArray(out, "indices", mesh.indices, 14, false);
        out.append("            }");
    }

    private static void appendBlockJson(
        StringBuilder out,
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models,
        String id,
        Set<String> allowedStateKeys
    ) {
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(id);
        net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(location);
        List<net.minecraft.world.level.block.state.BlockState> states = filterStates(block.getStateDefinition().getPossibleStates(), allowedStateKeys);
        if (allowedStateKeys != null && !allowedStateKeys.isEmpty() && states.isEmpty()) {
            System.out.println("[BlockPrintModelBaker] WARNING: no block states matched " + id + " selectors " + allowedStateKeys);
        }
        List<StateSample> samples = collectStateSamples(modelManager, models, states, false);
        out.append("    {\n");
        appendJsonField(out, "id", id, 6, true);
        out.append("      \"stateCount\": ").append(states.size()).append(",\n");
        out.append("      \"nonEmptyStateCount\": ").append(samples.stream().filter(sample -> sample.quads.size() > 0).count()).append(",\n");
        out.append("      \"maxQuadCount\": ").append(samples.stream().mapToInt(sample -> sample.quads.size()).max().orElse(0)).append(",\n");
        out.append("      \"sampleStates\": [\n");
        List<StateSample> selectedSamples = selectInterestingSamples(samples, 4);
        for (int i = 0; i < selectedSamples.size(); i++) {
            if (i > 0) {
                out.append(",\n");
            }
            appendStateJson(out, selectedSamples.get(i));
        }
        out.append("\n      ]\n");
        out.append("    }");
    }

    private static List<net.minecraft.world.level.block.state.BlockState> filterStates(
        List<net.minecraft.world.level.block.state.BlockState> states,
        Set<String> allowedStateKeys
    ) {
        if (allowedStateKeys == null || allowedStateKeys.isEmpty()) {
            return states;
        }
        return states.stream()
            .filter(state -> allowedStateKeys.contains(stateKey(state)))
            .collect(Collectors.toList());
    }

    private static List<StateSample> collectStateSamples(
        net.minecraft.client.resources.model.ModelManager modelManager,
        Map<net.minecraft.client.resources.model.ModelResourceLocation, net.minecraft.client.resources.model.BakedModel> models,
        List<net.minecraft.world.level.block.state.BlockState> states,
        boolean includeRendererMeshes
    ) {
        List<StateSample> samples = new ArrayList<>();
        for (net.minecraft.world.level.block.state.BlockState state : states) {
            net.minecraft.client.resources.model.ModelResourceLocation modelLocation = net.minecraft.client.renderer.block.BlockModelShaper.stateToModelLocation(state);
            net.minecraft.client.resources.model.BakedModel model = models.getOrDefault(modelLocation, modelManager.getMissingModel());
            List<QuadSample> quads = collectQuads(model, state);
            List<MeshAccumulator> rendererMeshes = List.of();
            if (includeRendererMeshes) {
                List<MeshAccumulator> recordedBlock = collectStandardBlockMeshes(model, state);
                List<MeshAccumulator> recordedExtra = collectRendererAndVisualMeshes(state);
                List<MeshAccumulator> combined = new ArrayList<>(recordedBlock.size() + recordedExtra.size());
                combined.addAll(recordedBlock);
                combined.addAll(recordedExtra);
                rendererMeshes = combined;
                if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL || !recordedBlock.isEmpty()) {
                    // The standard dispatcher has already emitted the baked model layer through the
                    // same final VertexConsumer boundary used by Minecraft, or Minecraft marks the
                    // state as ENTITYBLOCK_ANIMATED/INVISIBLE and never renders its baked model in
                    // the chunk at all. Keeping getQuads would duplicate renderer/visual geometry.
                    quads = List.of();
                }
            }
            samples.add(new StateSample(state, modelLocation, model, quads, rendererMeshes));
        }
        return samples;
    }

    private static List<MeshAccumulator> collectStandardBlockMeshes(
        net.minecraft.client.resources.model.BakedModel model,
        net.minecraft.world.level.block.state.BlockState state
    ) {
        if (!Boolean.parseBoolean(System.getProperty("blockprint.baker.captureBlockDispatcher", "true"))) {
            return List.of();
        }
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            return List.of();
        }

        CaptureLevel level = CaptureLevel.create(state);
        net.neoforged.neoforge.client.model.data.ModelData modelData;
        try {
            modelData = model.getModelData(
                level,
                net.minecraft.core.BlockPos.ZERO,
                state,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY
            );
        } catch (Throwable t) {
            modelData = net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
        }

        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
        List<net.minecraft.client.renderer.RenderType> renderTypes;
        try {
            renderTypes = model.getRenderTypes(state, random, modelData).asList();
        } catch (Throwable t) {
            renderTypes = List.of(net.minecraft.client.renderer.RenderType.solid());
        }
        if (renderTypes.isEmpty()) {
            renderTypes = List.of(net.minecraft.client.renderer.RenderType.solid());
        }

        List<MeshAccumulator> out = new ArrayList<>();
        net.minecraft.client.renderer.block.BlockRenderDispatcher dispatcher =
            net.minecraft.client.Minecraft.getInstance().getBlockRenderer();
        for (net.minecraft.client.renderer.RenderType renderType : renderTypes) {
            RecordingVertexConsumer consumer = new RecordingVertexConsumer(
                textureForRenderType(renderType),
                renderType.mode == com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES
            );
            try {
                random.setSeed(42L);
                com.mojang.blaze3d.vertex.PoseStack poseStack = new com.mojang.blaze3d.vertex.PoseStack();
                dispatcher.renderBatched(
                    state,
                    net.minecraft.core.BlockPos.ZERO,
                    level,
                    poseStack,
                    consumer,
                    false,
                    random,
                    modelData,
                    renderType
                );
                consumer.finish();
                if (consumer.mesh.vertexCount() > 0 && !consumer.mesh.indices.isEmpty()) {
                    out.addAll(resolveRecordedAtlasMesh(consumer.mesh));
                }
            } catch (Throwable t) {
                System.out.println(
                    "[BlockPrintModelBaker] Block dispatcher capture failed for " + state +
                    " / " + renderTypeName(renderType) + ": " + t.getClass().getName() + ": " + t.getMessage()
                );
            }
        }
        if (!out.isEmpty()) {
            System.out.println(
                "[BlockPrintModelBaker] Captured " + out.stream().mapToInt(MeshAccumulator::vertexCount).sum() +
                " standard block vertices for " + state
            );
        }
        return out;
    }

    private static List<MeshAccumulator> collectRendererAndVisualMeshes(net.minecraft.world.level.block.state.BlockState state) {
        List<MeshAccumulator> visualMeshes = collectFlywheelVisualMeshes(state);
        if (!visualMeshes.isEmpty()) {
            // Flywheel's model boundary retains source-sprite provenance and the real
            // index sequence. Prefer it when present; recording the BER too would
            // duplicate the same assembly and may lose sprite identity after a mod
            // clones a quad and rewrites only its UVs.
            return visualMeshes;
        }
        return collectRendererMeshes(state);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<MeshAccumulator> collectRendererMeshes(net.minecraft.world.level.block.state.BlockState state) {
        if (!Boolean.parseBoolean(System.getProperty("blockprint.baker.captureBlockEntityRenderers", "true"))) {
            return List.of();
        }
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) {
            return List.of();
        }
        CaptureLevel level;
        try {
            level = CaptureLevel.create(state);
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Renderer capture skipped for " + state + ": CaptureLevel failed: " + t.getClass().getName() + ": " + t.getMessage());
            return List.of();
        }
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.defaultBlockEntity();
        if (blockEntity == null) {
            return List.of();
        }

        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher dispatcher = minecraft.getBlockEntityRenderDispatcher();
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer;
        try {
            renderer = dispatcher.getRenderer(blockEntity);
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Renderer capture skipped for " + state + ": getRenderer failed: " + t.getClass().getName() + ": " + t.getMessage());
            return List.of();
        }
        if (renderer == null) {
            return List.of();
        }

        RecordingMultiBufferSource buffers = new RecordingMultiBufferSource();
        try {
            com.mojang.blaze3d.vertex.PoseStack poseStack = new com.mojang.blaze3d.vertex.PoseStack();
            renderer.render(blockEntity, 0.0f, poseStack, buffers, 0x00F000F0, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            List<MeshAccumulator> meshes = buffers.finish();
            if (!meshes.isEmpty()) {
                System.out.println("[BlockPrintModelBaker] Captured " + meshes.stream().mapToInt(MeshAccumulator::vertexCount).sum() + " BER vertices for " + state + " via " + renderer.getClass().getName());
            }
            return meshes;
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Renderer capture failed for " + state + " via " + renderer.getClass().getName() + ": " + t.getClass().getName() + ": " + t.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<MeshAccumulator> collectFlywheelVisualMeshes(net.minecraft.world.level.block.state.BlockState state) {
        if (!Boolean.parseBoolean(System.getProperty("blockprint.baker.captureFlywheelVisuals", "true"))) {
            return List.of();
        }
        atlasDiagnosticSamples = 0;
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) {
            return List.of();
        }
        CaptureLevel level;
        try {
            level = CaptureLevel.create(state);
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Flywheel capture skipped for " + state + ": CaptureLevel failed: " + t.getClass().getName() + ": " + t.getMessage());
            return List.of();
        }
        net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.defaultBlockEntity();
        if (blockEntity == null) {
            return List.of();
        }

        dev.engine_room.flywheel.api.visualization.BlockEntityVisualizer visualizer;
        try {
            visualizer = dev.engine_room.flywheel.api.visualization.VisualizerRegistry.getVisualizer(blockEntity.getType());
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Flywheel capture skipped for " + state + ": getVisualizer failed: " + t.getClass().getName() + ": " + t.getMessage());
            return List.of();
        }
        if (visualizer == null) {
            return List.of();
        }

        net.minecraft.resources.ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String textureNamespace = blockId == null ? "minecraft" : blockId.getNamespace();
        CapturingVisualizationContext context = new CapturingVisualizationContext(textureNamespace);
        dev.engine_room.flywheel.api.visual.BlockEntityVisual visual = null;
        try {
            visual = visualizer.createVisual(context, blockEntity, 0.0f);
            if (visual != null) {
                try {
                    visual.update(0.0f);
                } catch (Throwable ignored) {
                }
            }
            List<MeshAccumulator> meshes = context.finish();
            if (!meshes.isEmpty()) {
                System.out.println("[BlockPrintModelBaker] Captured " + meshes.stream().mapToInt(MeshAccumulator::vertexCount).sum() + " Flywheel vertices for " + state + " via " + visualizer.getClass().getName());
            }
            return meshes;
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Flywheel capture failed for " + state + " via " + visualizer.getClass().getName() + ": " + t.getClass().getName() + ": " + t.getMessage());
            return List.of();
        } finally {
            if (visual != null) {
                try {
                    visual.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<StateSample> selectInterestingSamples(List<StateSample> samples, int limit) {
        List<StateSample> selected = new ArrayList<>();
        for (StateSample sample : samples) {
            if (sample.quads.size() > 0) {
                selected.add(sample);
                if (selected.size() == limit) {
                    return selected;
                }
            }
        }
        for (StateSample sample : samples) {
            if (!selected.contains(sample)) {
                selected.add(sample);
                if (selected.size() == limit) {
                    return selected;
                }
            }
        }
        return selected;
    }

    private static void appendStateJson(StringBuilder out, StateSample sample) {
        out.append("        {\n");
        appendJsonField(out, "state", sample.state.toString(), 10, true);
        appendJsonField(out, "modelLocation", sample.modelLocation.toString(), 10, true);
        appendJsonField(out, "modelClass", sample.model.getClass().getName(), 10, true);
        out.append("          \"customRenderer\": ").append(sample.model.isCustomRenderer()).append(",\n");
        out.append("          \"quadCount\": ").append(sample.quads.size()).append(",\n");
        out.append("          \"quads\": [\n");
        int limit = Math.min(sample.quads.size(), 16);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                out.append(",\n");
            }
            appendQuadJson(out, sample.quads.get(i));
        }
        out.append("\n          ]\n");
        out.append("        }");
    }

    private static List<QuadSample> collectQuads(net.minecraft.client.resources.model.BakedModel model, net.minecraft.world.level.block.state.BlockState state) {
        List<QuadSample> result = new ArrayList<>();
        List<net.minecraft.core.Direction> sides = new ArrayList<>();
        sides.add(null);
        sides.addAll(Arrays.asList(net.minecraft.core.Direction.values()));

        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(42L);
        net.neoforged.neoforge.client.model.data.ModelData modelData = collectModelData(model, state);
        List<net.minecraft.client.renderer.RenderType> renderTypes = model.getRenderTypes(state, random, modelData).asList();
        if (renderTypes.isEmpty()) {
            renderTypes = Arrays.asList((net.minecraft.client.renderer.RenderType) null);
        }

        for (net.minecraft.client.renderer.RenderType renderType : renderTypes) {
            for (net.minecraft.core.Direction side : sides) {
                random.setSeed(42L);
                List<net.minecraft.client.renderer.block.model.BakedQuad> quads = model.getQuads(state, side, random, modelData, renderType);
                for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
                    result.add(new QuadSample(renderTypeName(renderType), sideName(side), quad));
                }
            }
        }
        return result;
    }

    private static net.neoforged.neoforge.client.model.data.ModelData collectModelData(
        net.minecraft.client.resources.model.BakedModel model,
        net.minecraft.world.level.block.state.BlockState state
    ) {
        try {
            return model.getModelData(
                CaptureLevel.create(state),
                net.minecraft.core.BlockPos.ZERO,
                state,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY
            );
        } catch (Throwable t) {
            return net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
        }
    }

    private static ExportSelection configuredSelection() throws IOException {
        String explicitStateFile = System.getProperty("blockprint.baker.blockstateFile", "").trim();
        String explicitStates = System.getProperty("blockprint.baker.blockstates", "").trim();
        if (!explicitStateFile.isEmpty() || !explicitStates.isEmpty()) {
            LinkedHashMap<String, Set<String>> statesByBlock = new LinkedHashMap<>();
            if (!explicitStateFile.isEmpty()) {
                readBlockstateFile(Path.of(explicitStateFile), statesByBlock);
            }
            if (!explicitStates.isEmpty()) {
                parseBlockstateList(explicitStates, statesByBlock);
            }
            return ExportSelection.forStateKeys(statesByBlock, "blockstates");
        }

        String explicitBlocks = System.getProperty("blockprint.baker.blocks", "").trim();
        if (!explicitBlocks.isEmpty()) {
            List<String> ids = Arrays.stream(explicitBlocks.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
            return ExportSelection.forBlocks(ids, "blocks");
        }

        String explicitNamespaces = System.getProperty("blockprint.baker.namespaces",
            System.getProperty("blockprint.baker.modids", "")).trim();
        if (!explicitNamespaces.isEmpty()) {
            List<String> namespaces = Arrays.stream(explicitNamespaces.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
            List<String> ids = net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().stream()
                .filter(id -> namespaces.contains(id.getNamespace()))
                .map(net.minecraft.resources.ResourceLocation::toString)
                .sorted()
                .collect(Collectors.toList());
            return ExportSelection.forBlocks(ids, "namespaces=" + String.join(",", namespaces));
        }

        return ExportSelection.forBlocks(Arrays.asList("minecraft:stone", "create:fluid_pipe", "create:mechanical_arm"), "default probe");
    }

    private static void readBlockstateFile(Path path, LinkedHashMap<String, Set<String>> out) throws IOException {
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            addBlockstateSelector(line, out);
        }
    }

    private static void parseBlockstateList(String value, LinkedHashMap<String, Set<String>> out) {
        for (String item : value.split(";")) {
            String line = item.strip();
            if (line.isEmpty()) continue;
            addBlockstateSelector(line, out);
        }
    }

    private static void addBlockstateSelector(String selector, LinkedHashMap<String, Set<String>> out) {
        int bracket = selector.indexOf('[');
        String id = bracket >= 0 ? selector.substring(0, bracket).trim() : selector.trim();
        if (id.isEmpty()) return;
        if (bracket < 0) {
            out.putIfAbsent(id, new LinkedHashSet<>());
            return;
        }
        int end = selector.lastIndexOf(']');
        String props = end > bracket ? selector.substring(bracket + 1, end) : selector.substring(bracket + 1);
        String key = normalizeStateKey(props);
        out.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(key);
    }

    private static String normalizeStateKey(String props) {
        if (props == null || props.isBlank()) return "";
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String part : props.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] kv = trimmed.split("=", 2);
            if (kv.length == 2) {
                sorted.put(kv[0].trim(), kv[1].trim());
            }
        }
        return sorted.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(","));
    }

    private static String manifestFileName() {
        String explicit = System.getProperty("blockprint.baker.manifestName", "").trim();
        if (!explicit.isEmpty()) {
            return explicit.endsWith(".json") ? explicit : explicit + ".json";
        }
        String namespaces = System.getProperty("blockprint.baker.namespaces", "").trim();
        if (!namespaces.isEmpty() && !namespaces.contains(",")) {
            return namespaces.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".json";
        }
        return "selected.json";
    }

    private static String stateKey(net.minecraft.world.level.block.state.BlockState state) {
        Map<String, String> values = new TreeMap<>();
        for (Map.Entry<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            values.put(entry.getKey().getName(), propertyValueName(entry.getKey(), entry.getValue()));
        }
        return values.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(","));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyValueName(net.minecraft.world.level.block.state.properties.Property property, Comparable value) {
        return property.getName(value);
    }

    private static String texturePath(net.minecraft.resources.ResourceLocation sprite) {
        return sprite.getNamespace() + ":textures/" + sprite.getPath();
    }

    private static String texturePath(Object spriteName) {
        if (spriteName instanceof net.minecraft.resources.ResourceLocation location) {
            return texturePath(location);
        }
        return null;
    }

    private static float localU(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, float u) {
        float span = sprite.getU1() - sprite.getU0();
        if (span == 0f) {
            return 0f;
        }
        return (u - sprite.getU0()) / span;
    }

    private static float localV(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, float v) {
        float span = sprite.getV1() - sprite.getV0();
        if (span == 0f) {
            return 0f;
        }
        return (v - sprite.getV0()) / span;
    }

    private static float localUv(float min, float max, float value) {
        float span = max - min;
        if (span == 0f) {
            return 0f;
        }
        return (value - min) / span;
    }

    private static float normalComponent(net.minecraft.core.Direction direction, int axis) {
        net.minecraft.core.Vec3i normal = direction.getNormal();
        return switch (axis) {
            case 0 -> normal.getX();
            case 1 -> normal.getY();
            default -> normal.getZ();
        };
    }

    private static void appendQuadJson(StringBuilder out, QuadSample sample) {
        net.minecraft.client.renderer.block.model.BakedQuad quad = sample.quad;
        out.append("            {\n");
        appendJsonField(out, "renderType", sample.renderType, 14, true);
        appendJsonField(out, "cullSide", sample.cullSide, 14, true);
        appendJsonField(out, "direction", quad.getDirection().getName(), 14, true);
        appendJsonField(out, "sprite", quad.getSprite().contents().name().toString(), 14, true);
        out.append("              \"tintIndex\": ").append(quad.getTintIndex()).append(",\n");
        out.append("              \"shade\": ").append(quad.isShade()).append(",\n");
        out.append("              \"vertices\": [\n");
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                out.append(",\n");
            }
            int base = i * stride;
            float x = Float.intBitsToFloat(vertices[base]);
            float y = Float.intBitsToFloat(vertices[base + 1]);
            float z = Float.intBitsToFloat(vertices[base + 2]);
            float u = stride > 4 ? Float.intBitsToFloat(vertices[base + 4]) : 0f;
            float v = stride > 5 ? Float.intBitsToFloat(vertices[base + 5]) : 0f;
            out.append("                {\"x\": ").append(formatFloat(x))
                .append(", \"y\": ").append(formatFloat(y))
                .append(", \"z\": ").append(formatFloat(z))
                .append(", \"u\": ").append(formatFloat(u))
                .append(", \"v\": ").append(formatFloat(v))
                .append("}");
        }
        out.append("\n              ]\n");
        out.append("            }");
    }

    private static void appendJsonField(StringBuilder out, String name, String value, int indent, boolean comma) {
        out.append(" ".repeat(indent)).append("\"").append(name).append("\": \"").append(escapeJson(value)).append("\"");
        if (comma) {
            out.append(",");
        }
        out.append("\n");
    }

    private static void appendFloatArray(StringBuilder out, String name, List<Float> values, int indent, boolean comma) {
        out.append(" ".repeat(indent)).append("\"").append(name).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(",");
            }
            out.append(formatFloat(values.get(i)));
        }
        out.append("]");
        if (comma) {
            out.append(",");
        }
        out.append("\n");
    }

    private static void appendIntArray(StringBuilder out, String name, List<Integer> values, int indent, boolean comma) {
        out.append(" ".repeat(indent)).append("\"").append(name).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(",");
            }
            out.append(values.get(i));
        }
        out.append("]");
        if (comma) {
            out.append(",");
        }
        out.append("\n");
    }

    private static String renderTypeName(net.minecraft.client.renderer.RenderType renderType) {
        return renderType == null ? "default" : renderType.toString();
    }

    private static final String ATLAS_TEXTURE_PREFIX = "@atlas:";
    private static int atlasDiagnosticSamples;

    private static String atlasTextureMarker(net.minecraft.resources.ResourceLocation location) {
        return ATLAS_TEXTURE_PREFIX + location;
    }

    private static boolean isAtlasTextureMarker(String texture) {
        return texture != null && texture.startsWith(ATLAS_TEXTURE_PREFIX);
    }

    private static net.minecraft.resources.ResourceLocation atlasLocationFromMarker(String texture) {
        if (!isAtlasTextureMarker(texture)) {
            return null;
        }
        return net.minecraft.resources.ResourceLocation.tryParse(texture.substring(ATLAS_TEXTURE_PREFIX.length()));
    }

    private static String textureForRenderType(net.minecraft.client.renderer.RenderType renderType) {
        String fallback = System.getProperty("blockprint.baker.rendererFallbackTexture", "minecraft:textures/block/white_concrete");
        if (renderType == null) {
            return fallback;
        }
        try {
            Class<?> compositeRenderType = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
            if (!compositeRenderType.isInstance(renderType)) {
                return fallback;
            }
            java.lang.reflect.Field stateField = compositeRenderType.getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(renderType);
            java.lang.reflect.Field textureStateField = state.getClass().getDeclaredField("textureState");
            textureStateField.setAccessible(true);
            Object textureState = textureStateField.get(state);
            java.lang.reflect.Field textureField = findField(textureState.getClass(), "texture");
            if (textureField == null) {
                return fallback;
            }
            textureField.setAccessible(true);
            Object value = textureField.get(textureState);
            if (value instanceof java.util.Optional<?> optional && optional.isPresent()) {
                Object texture = optional.get();
                if (texture instanceof net.minecraft.resources.ResourceLocation location) {
                    String path = location.getPath();
                    if (path.startsWith("textures/atlas/")) {
                        return atlasTextureMarker(location);
                    }
                    if (path.startsWith("textures/")) {
                        path = path.substring("textures/".length());
                    }
                    if (path.endsWith(".png")) {
                        path = path.substring(0, path.length() - 4);
                    }
                    return location.getNamespace() + ":textures/" + path;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static java.lang.reflect.Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String sideName(net.minecraft.core.Direction side) {
        return side == null ? "unculled" : side.getName();
    }

    private static String formatFloat(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return "0.0";
        }
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    private record QuadSample(String renderType, String cullSide, net.minecraft.client.renderer.block.model.BakedQuad quad) {}

    private record StateSample(
        net.minecraft.world.level.block.state.BlockState state,
        net.minecraft.client.resources.model.ModelResourceLocation modelLocation,
        net.minecraft.client.resources.model.BakedModel model,
        List<QuadSample> quads,
        List<MeshAccumulator> rendererMeshes
    ) {}

    private record ExportSelection(
        List<String> blockIds,
        Map<String, Set<String>> stateKeysByBlock,
        String sourceDescription
    ) {
        static ExportSelection forBlocks(List<String> ids, String sourceDescription) {
            return new ExportSelection(ids.stream().distinct().sorted().collect(Collectors.toList()), Map.of(), sourceDescription);
        }

        static ExportSelection forStateKeys(LinkedHashMap<String, Set<String>> stateKeysByBlock, String sourceDescription) {
            return new ExportSelection(
                stateKeysByBlock.keySet().stream().distinct().sorted().collect(Collectors.toList()),
                stateKeysByBlock,
                sourceDescription
            );
        }

        Set<String> stateKeysFor(String id) {
            return stateKeysByBlock.getOrDefault(id, Set.of());
        }

        int constrainedStateCount() {
            return stateKeysByBlock.values().stream().mapToInt(Set::size).sum();
        }

        ExportSelection only(String id) {
            Set<String> keys = stateKeysFor(id);
            Map<String, Set<String>> map = keys.isEmpty() ? Map.of() : Map.of(id, keys);
            return new ExportSelection(List.of(id), map, sourceDescription);
        }
    }

    private static final class MeshAccumulator {
        private final String texture;
        private final List<Float> positions = new ArrayList<>();
        private final List<Float> uvs = new ArrayList<>();
        private final List<Float> normals = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

        private MeshAccumulator(String texture) {
            this.texture = texture;
        }

        private void add(net.minecraft.client.renderer.block.model.BakedQuad quad) {
            int baseVertex = positions.size() / 3;
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = quad.getSprite();
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                positions.add(Float.intBitsToFloat(vertices[base]) * 16f);
                positions.add(Float.intBitsToFloat(vertices[base + 1]) * 16f);
                positions.add(Float.intBitsToFloat(vertices[base + 2]) * 16f);
                float u = stride > 4 ? Float.intBitsToFloat(vertices[base + 4]) : sprite.getU0();
                float v = stride > 5 ? Float.intBitsToFloat(vertices[base + 5]) : sprite.getV0();
                uvs.add(localU(sprite, u));
                uvs.add(localV(sprite, v));
                normals.add(normalComponent(quad.getDirection(), 0));
                normals.add(normalComponent(quad.getDirection(), 1));
                normals.add(normalComponent(quad.getDirection(), 2));
            }
            indices.add(baseVertex);
            indices.add(baseVertex + 1);
            indices.add(baseVertex + 2);
            indices.add(baseVertex);
            indices.add(baseVertex + 2);
            indices.add(baseVertex + 3);
        }

        private void addVertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, boolean triangleMode) {
            int vertex = positions.size() / 3;
            positions.add(x * 16f);
            positions.add(y * 16f);
            positions.add(z * 16f);
            uvs.add(u);
            uvs.add(v);
            normals.add(nx);
            normals.add(ny);
            normals.add(nz);

            if (triangleMode) {
                if ((vertex + 1) % 3 == 0) {
                    indices.add(vertex - 2);
                    indices.add(vertex - 1);
                    indices.add(vertex);
                }
            } else if ((vertex + 1) % 4 == 0) {
                int base = vertex - 3;
                indices.add(base);
                indices.add(base + 1);
                indices.add(base + 2);
                indices.add(base);
                indices.add(base + 2);
                indices.add(base + 3);
            }
        }

        private void addStoredTriangle(
            MeshAccumulator source,
            int i0,
            int i1,
            int i2,
            TextureMapping mapping
        ) {
            int base = vertexCount();
            addStoredVertex(source, i0, mapping);
            addStoredVertex(source, i1, mapping);
            addStoredVertex(source, i2, mapping);
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
        }

        private void addStoredVertex(MeshAccumulator source, int index, TextureMapping mapping) {
            int positionOffset = index * 3;
            int uvOffset = index * 2;
            positions.add(source.positions.get(positionOffset));
            positions.add(source.positions.get(positionOffset + 1));
            positions.add(source.positions.get(positionOffset + 2));
            uvs.add(mapping.localU(source.uvs.get(uvOffset)));
            uvs.add(mapping.localV(source.uvs.get(uvOffset + 1)));
            normals.add(source.normals.get(positionOffset));
            normals.add(source.normals.get(positionOffset + 1));
            normals.add(source.normals.get(positionOffset + 2));
        }

        private MeshAccumulator merge(MeshAccumulator other) {
            int baseVertex = positions.size() / 3;
            positions.addAll(other.positions);
            uvs.addAll(other.uvs);
            normals.addAll(other.normals);
            for (int index : other.indices) {
                indices.add(baseVertex + index);
            }
            return this;
        }

        private int vertexCount() {
            return positions.size() / 3;
        }
    }

    private static final class RecordingMultiBufferSource implements net.minecraft.client.renderer.MultiBufferSource {
        private final Map<String, RecordingVertexConsumer> consumers = new LinkedHashMap<>();

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType renderType) {
            String texture = textureForRenderType(renderType);
            boolean triangleMode = renderType != null && renderType.mode == com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES;
            String key = texture + "|" + renderTypeName(renderType);
            return consumers.computeIfAbsent(key, ignored -> new RecordingVertexConsumer(texture, triangleMode));
        }

        private List<MeshAccumulator> finish() {
            List<MeshAccumulator> out = new ArrayList<>();
            for (RecordingVertexConsumer consumer : consumers.values()) {
                consumer.finish();
                if (consumer.mesh.vertexCount() > 0 && !consumer.mesh.indices.isEmpty()) {
                    out.addAll(resolveRecordedAtlasMesh(consumer.mesh));
                }
            }
            return out;
        }
    }

    private static final class RecordingVertexConsumer implements com.mojang.blaze3d.vertex.VertexConsumer {
        private final MeshAccumulator mesh;
        private final boolean triangleMode;
        private boolean hasPendingVertex;
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;
        private float nx;
        private float ny = 1.0f;
        private float nz;

        private RecordingVertexConsumer(String texture, boolean triangleMode) {
            this.mesh = new MeshAccumulator(texture);
            this.triangleMode = triangleMode;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            flushPending();
            this.hasPendingVertex = true;
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0.0f;
            this.v = 0.0f;
            this.nx = 0.0f;
            this.ny = 1.0f;
            this.nz = 0.0f;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            this.nx = normalX;
            this.ny = normalY;
            this.nz = normalZ;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(org.joml.Matrix4f pose, float x, float y, float z) {
            Vector3f transformed = pose.transformPosition(x, y, z, new Vector3f());
            return addVertex(transformed.x(), transformed.y(), transformed.z());
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(com.mojang.blaze3d.vertex.PoseStack.Pose pose, float normalX, float normalY, float normalZ) {
            Vector3f transformed = pose.normal().transform(normalX, normalY, normalZ, new Vector3f());
            return setNormal(transformed.x(), transformed.y(), transformed.z());
        }

        private void finish() {
            flushPending();
        }

        private void flushPending() {
            if (!hasPendingVertex) {
                return;
            }
            mesh.addVertex(x, y, z, u, v, nx, ny, nz, triangleMode);
            hasPendingVertex = false;
        }
    }

    private static final class CapturingVisualizationContext implements dev.engine_room.flywheel.api.visualization.VisualizationContext {
        private final CapturingInstancerProvider instancerProvider;

        private CapturingVisualizationContext(String textureNamespace) {
            this.instancerProvider = new CapturingInstancerProvider(textureNamespace);
        }

        @Override
        public dev.engine_room.flywheel.api.instance.InstancerProvider instancerProvider() {
            return instancerProvider;
        }

        @Override
        public net.minecraft.core.Vec3i renderOrigin() {
            return net.minecraft.core.Vec3i.ZERO;
        }

        @Override
        public dev.engine_room.flywheel.api.visualization.VisualEmbedding createEmbedding(net.minecraft.core.Vec3i renderOrigin) {
            return new CapturingVisualEmbedding(instancerProvider, renderOrigin);
        }

        private List<MeshAccumulator> finish() {
            return instancerProvider.finish(new Matrix4f());
        }
    }

    private static final class CapturingVisualEmbedding implements dev.engine_room.flywheel.api.visualization.VisualEmbedding {
        private final CapturingInstancerProvider instancerProvider;
        private final net.minecraft.core.Vec3i renderOrigin;
        private final Matrix4f pose = new Matrix4f();

        private CapturingVisualEmbedding(CapturingInstancerProvider instancerProvider, net.minecraft.core.Vec3i renderOrigin) {
            this.instancerProvider = instancerProvider;
            this.renderOrigin = renderOrigin;
        }

        @Override
        public dev.engine_room.flywheel.api.instance.InstancerProvider instancerProvider() {
            return instancerProvider;
        }

        @Override
        public net.minecraft.core.Vec3i renderOrigin() {
            return renderOrigin;
        }

        @Override
        public dev.engine_room.flywheel.api.visualization.VisualEmbedding createEmbedding(net.minecraft.core.Vec3i renderOrigin) {
            return new CapturingVisualEmbedding(instancerProvider, renderOrigin);
        }

        @Override
        public void transforms(org.joml.Matrix4fc pose, org.joml.Matrix3fc normal) {
            this.pose.set(pose);
        }

        @Override
        public void delete() {
        }
    }

    private static final class CapturingInstancerProvider implements dev.engine_room.flywheel.api.instance.InstancerProvider {
        private final List<CapturedInstance<?>> instances = new ArrayList<>();
        private final String textureNamespace;

        private CapturingInstancerProvider(String textureNamespace) {
            this.textureNamespace = textureNamespace;
        }

        @Override
        public <I extends dev.engine_room.flywheel.api.instance.Instance> dev.engine_room.flywheel.api.instance.Instancer<I> instancer(
            dev.engine_room.flywheel.api.instance.InstanceType<I> type,
            dev.engine_room.flywheel.api.model.Model model,
            int bias
        ) {
            return new CapturingInstancer<>(type, model, instances);
        }

        private List<MeshAccumulator> finish(Matrix4f embeddingTransform) {
            List<MeshAccumulator> out = new ArrayList<>();
            for (CapturedInstance<?> captured : instances) {
                if (!captured.handle.isVisible() || captured.handle.deleted) {
                    continue;
                }
                out.addAll(meshesForInstance(captured.model, captured.instance, embeddingTransform, textureNamespace));
            }
            return out;
        }
    }

    private static final class CapturingInstancer<I extends dev.engine_room.flywheel.api.instance.Instance> implements dev.engine_room.flywheel.api.instance.Instancer<I> {
        private final dev.engine_room.flywheel.api.instance.InstanceType<I> type;
        private final dev.engine_room.flywheel.api.model.Model model;
        private final List<CapturedInstance<?>> instances;

        private CapturingInstancer(
            dev.engine_room.flywheel.api.instance.InstanceType<I> type,
            dev.engine_room.flywheel.api.model.Model model,
            List<CapturedInstance<?>> instances
        ) {
            this.type = type;
            this.model = model;
            this.instances = instances;
        }

        @Override
        public I createInstance() {
            CapturingInstanceHandle handle = new CapturingInstanceHandle();
            I instance = type.create(handle);
            instances.add(new CapturedInstance<>(model, instance, handle));
            return instance;
        }

        @Override
        public void stealInstance(I instance) {
            instances.add(new CapturedInstance<>(model, instance, new CapturingInstanceHandle()));
        }
    }

    private record CapturedInstance<I extends dev.engine_room.flywheel.api.instance.Instance>(
        dev.engine_room.flywheel.api.model.Model model,
        I instance,
        CapturingInstanceHandle handle
    ) {}

    private static final class CapturingInstanceHandle implements dev.engine_room.flywheel.api.instance.InstanceHandle {
        private boolean visible = true;
        private boolean deleted = false;
        private boolean changed = false;

        @Override
        public void setChanged() {
            changed = true;
        }

        @Override
        public void setDeleted() {
            deleted = true;
        }

        @Override
        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        @Override
        public boolean isVisible() {
            return visible;
        }
    }

    private static List<MeshAccumulator> meshesForInstance(
        dev.engine_room.flywheel.api.model.Model model,
        dev.engine_room.flywheel.api.instance.Instance instance,
        Matrix4f embeddingTransform,
        String textureNamespace
    ) {
        List<MeshAccumulator> out = new ArrayList<>();
        Matrix4f transform = instanceTransform(instance);
        embeddingTransform.mul(transform, transform);
        Matrix3f normalTransform = new Matrix3f(transform).invert().transpose();

        for (dev.engine_room.flywheel.api.model.Model.ConfiguredMesh configuredMesh : model.meshes()) {
            dev.engine_room.flywheel.api.model.Mesh mesh = configuredMesh.mesh();
            int vertexCount = mesh.vertexCount();
            if (vertexCount <= 0) {
                continue;
            }
            CapturedVertexList vertices = new CapturedVertexList(vertexCount);
            mesh.write(vertices);

            int[] triangleIndices = meshTriangleIndices(mesh);
            List<net.minecraft.client.renderer.texture.TextureAtlasSprite> provenanceSprites =
                FlywheelTextureProvenance.spritesFor(mesh);
            List<TextureMapping> provenanceMappings = inferProvenanceMappings(
                configuredMesh.material(), provenanceSprites, vertices
            );
            Map<String, MeshAccumulator> accumulators = new LinkedHashMap<>();
            for (int start = 0; start + 2 < triangleIndices.length; start += 3) {
                int i0 = triangleIndices[start];
                int i1 = triangleIndices[start + 1];
                int i2 = triangleIndices[start + 2];
                if (i0 < 0 || i0 >= vertexCount || i1 < 0 || i1 >= vertexCount || i2 < 0 || i2 >= vertexCount) {
                    continue;
                }
                int[] primitiveIndices = {i0, i1, i2};
                int quadIndex = start / 6;
                TextureMapping mapping = quadIndex < provenanceMappings.size()
                    ? provenanceMappings.get(quadIndex)
                    : textureMappingForPrimitive(
                        configuredMesh.material(), vertices, primitiveIndices, textureNamespace
                    );
                MeshAccumulator acc = accumulators.computeIfAbsent(mapping.texture(), MeshAccumulator::new);
                for (int i : primitiveIndices) {
                    Vector3f position = transform.transformPosition(vertices.x[i], vertices.y[i], vertices.z[i], new Vector3f());
                    Vector3f normal = normalTransform.transform(vertices.nx[i], vertices.ny[i], vertices.nz[i], new Vector3f());
                    if (normal.lengthSquared() > 0.0f) {
                        normal.normalize();
                    } else {
                        normal.set(0.0f, 1.0f, 0.0f);
                    }
                    acc.addVertex(
                        position.x(), position.y(), position.z(),
                        mapping.localU(vertices.u[i]), mapping.localV(vertices.v[i]),
                        normal.x(), normal.y(), normal.z(),
                        true
                    );
                }
            }
            for (MeshAccumulator acc : accumulators.values()) {
                if (acc.vertexCount() > 0 && !acc.indices.isEmpty()) {
                    out.addAll(resolveRecordedAtlasMesh(acc));
                }
            }
        }
        return out;
    }

    private static List<TextureMapping> inferProvenanceMappings(
        dev.engine_room.flywheel.api.material.Material material,
        List<net.minecraft.client.renderer.texture.TextureAtlasSprite> provenanceSprites,
        CapturedVertexList vertices
    ) {
        if (provenanceSprites.isEmpty()) {
            return List.of();
        }

        Map<net.minecraft.resources.ResourceLocation, Map<AtlasSpriteInfo, Integer>> targetsBySource =
            new LinkedHashMap<>();
        if (materialUsesAtlas(material)) {
            int count = Math.min(provenanceSprites.size(), vertices.vertexCount() / 4);
            for (int quadIndex = 0; quadIndex < count; quadIndex++) {
                net.minecraft.client.renderer.texture.TextureAtlasSprite source = provenanceSprites.get(quadIndex);
                AtlasSpriteInfo target = strictAtlasSpriteForVertexRange(
                    material.texture(), vertices, quadIndex * 4, 4
                );
                if (source == null || target == null) {
                    continue;
                }
                targetsBySource
                    .computeIfAbsent(source.contents().name(), ignored -> new LinkedHashMap<>())
                    .merge(target, 1, Integer::sum);
            }
        }

        Map<net.minecraft.resources.ResourceLocation, AtlasSpriteInfo> preferredTargets = new LinkedHashMap<>();
        for (Map.Entry<net.minecraft.resources.ResourceLocation, Map<AtlasSpriteInfo, Integer>> entry : targetsBySource.entrySet()) {
            AtlasSpriteInfo preferred = null;
            int bestCount = 0;
            for (Map.Entry<AtlasSpriteInfo, Integer> candidate : entry.getValue().entrySet()) {
                if (candidate.getValue() > bestCount) {
                    preferred = candidate.getKey();
                    bestCount = candidate.getValue();
                }
            }
            if (preferred != null) {
                preferredTargets.put(entry.getKey(), preferred);
            }
        }

        List<TextureMapping> mappings = new ArrayList<>(provenanceSprites.size());
        for (int quadIndex = 0; quadIndex < provenanceSprites.size(); quadIndex++) {
            net.minecraft.client.renderer.texture.TextureAtlasSprite source = provenanceSprites.get(quadIndex);
            AtlasSpriteInfo preferred = source == null ? null : preferredTargets.get(source.contents().name());
            mappings.add(preferred != null
                ? preferred.mapping()
                : textureMappingFromProvenance(material, source, vertices, quadIndex));
        }
        return mappings;
    }

    private static AtlasSpriteInfo strictAtlasSpriteForVertexRange(
        net.minecraft.resources.ResourceLocation atlasLocation,
        CapturedVertexList vertices,
        int start,
        int count
    ) {
        if (start < 0 || count <= 0 || start + count > vertices.vertexCount()) {
            return null;
        }
        float centerU = 0.0f;
        float centerV = 0.0f;
        for (int i = start; i < start + count; i++) {
            if (!Float.isFinite(vertices.u[i]) || !Float.isFinite(vertices.v[i])) {
                return null;
            }
            centerU += vertices.u[i];
            centerV += vertices.v[i];
        }
        centerU /= count;
        centerV /= count;
        AtlasSpriteInfo owner = spriteOwningUv(atlasLocation, centerU, centerV);
        if (owner == null) {
            return null;
        }
        for (int i = start; i < start + count; i++) {
            float insetU = vertices.u[i] * 0.999f + centerU * 0.001f;
            float insetV = vertices.v[i] * 0.999f + centerV * 0.001f;
            if (!owner.ownsUv(insetU, insetV)) {
                return null;
            }
        }
        return owner;
    }

    private static TextureMapping textureMappingFromProvenance(
        dev.engine_room.flywheel.api.material.Material material,
        net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
        CapturedVertexList vertices,
        int quadIndex
    ) {
        if (!materialUsesAtlas(material) || sprite == null) {
            return new TextureMapping(textureForMaterial(material), 0f, 1f, 0f, 1f, false);
        }

        int base = quadIndex * 4;
        if (base + 3 >= vertices.vertexCount()) {
            return new TextureMapping(texturePath(sprite.contents().name()), sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), true);
        }
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = base; i < base + 4; i++) {
            minU = Math.min(minU, vertices.u[i]);
            maxU = Math.max(maxU, vertices.u[i]);
            minV = Math.min(minV, vertices.v[i]);
            maxV = Math.max(maxV, vertices.v[i]);
        }

        float epsilon = Float.parseFloat(System.getProperty("blockprint.baker.atlasSpriteUvEpsilon", "0.00075"));
        boolean insideProvenanceSprite =
            minU >= sprite.getU0() - epsilon && maxU <= sprite.getU1() + epsilon &&
            minV >= sprite.getV0() - epsilon && maxV <= sprite.getV1() + epsilon;
        if (insideProvenanceSprite || maxU - minU < 0.000001f || maxV - minV < 0.000001f) {
            return new TextureMapping(
                texturePath(sprite.contents().name()),
                sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), true
            );
        }

        // Create and other mods sometimes rewrite BakedQuad UVs to a generated or
        // anonymous atlas slot while leaving the source sprite metadata intact.
        // The provenance supplies the logical texture ID; the quad's own UV bounds
        // preserve its orientation without requiring a block-specific rule.
        return new TextureMapping(
            texturePath(sprite.contents().name()),
            minU, maxU, minV, maxV, true
        );
    }

    private static int[] meshTriangleIndices(dev.engine_room.flywheel.api.model.Mesh mesh) {
        int indexCount = mesh.indexCount();
        if (indexCount > 0 && mesh.indexSequence() != null) {
            java.nio.ByteBuffer buffer = null;
            try {
                buffer = org.lwjgl.system.MemoryUtil.memAlloc(indexCount * Integer.BYTES)
                    .order(java.nio.ByteOrder.nativeOrder());
                mesh.indexSequence().fill(org.lwjgl.system.MemoryUtil.memAddress(buffer), indexCount);
                int triangleIndexCount = indexCount - indexCount % 3;
                int[] out = new int[triangleIndexCount];
                for (int i = 0; i < triangleIndexCount; i++) {
                    out[i] = buffer.getInt(i * Integer.BYTES);
                }
                return out;
            } catch (Throwable t) {
                System.out.println(
                    "[BlockPrintModelBaker] Flywheel index sequence capture failed for " + mesh.getClass().getName() +
                    ": " + t.getClass().getName() + ": " + t.getMessage()
                );
            } finally {
                if (buffer != null) {
                    org.lwjgl.system.MemoryUtil.memFree(buffer);
                }
            }
        }

        int vertexCount = mesh.vertexCount();
        if (vertexCount % 4 == 0) {
            int[] out = new int[(vertexCount / 4) * 6];
            int cursor = 0;
            for (int base = 0; base < vertexCount; base += 4) {
                out[cursor++] = base;
                out[cursor++] = base + 1;
                out[cursor++] = base + 2;
                out[cursor++] = base;
                out[cursor++] = base + 2;
                out[cursor++] = base + 3;
            }
            return out;
        }
        int count = vertexCount - vertexCount % 3;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = i;
        }
        return out;
    }

    private static Matrix4f instanceTransform(dev.engine_room.flywheel.api.instance.Instance instance) {
        // Reproduce the instance vertex shader on the CPU.  A raw quaternion
        // is not, by itself, the complete instance transform: Flywheel's
        // oriented instances rotate around their explicit pivot, while
        // Create's rotating/scrolling instances rotate around the centre of
        // the block.  Rotating around the origin displaced every non-Y-axis
        // shaft, cog and wheel by exactly one block (16 model units).
        Object pose = readField(instance, "pose");
        if (pose instanceof org.joml.Matrix4fc matrix) {
            // TransformedInstance, ScrollTransformedInstance and FluidInstance
            // shaders apply this matrix directly.
            return new Matrix4f(matrix);
        }

        Object rotation = readField(instance, "rotation");
        org.joml.Quaternionfc baseRotation = rotation instanceof org.joml.Quaternionfc quaternion
            ? quaternion
            : new Quaternionf();

        // Flywheel OrientedInstance uses posX/posY/posZ plus an arbitrary
        // pivot.  Match oriented.vert:
        //   rotate(vertex - pivot, rotation) + pivot + position
        if (hasField(instance, "posX") || hasField(instance, "pivotX")) {
            float tx = numberField(instance, "posX", 0.0f);
            float ty = numberField(instance, "posY", 0.0f);
            float tz = numberField(instance, "posZ", 0.0f);
            float pivotX = numberField(instance, "pivotX", 0.5f);
            float pivotY = numberField(instance, "pivotY", 0.5f);
            float pivotZ = numberField(instance, "pivotZ", 0.5f);
            return centeredTransform(tx, ty, tz, pivotX, pivotY, pivotZ, null, baseRotation);
        }

        float tx = numberField(instance, "x", 0.0f);
        float ty = numberField(instance, "y", 0.0f);
        float tz = numberField(instance, "z", 0.0f);

        // Create's ActorInstance exposes its shader pivot as normalized-byte
        // rotationCenter* fields.  RotatingInstance and ScrollInstance omit
        // them and use the block centre (0.5, 0.5, 0.5).
        float pivotX = normalizedByteField(instance, "rotationCenterX", 0.5f);
        float pivotY = normalizedByteField(instance, "rotationCenterY", 0.5f);
        float pivotZ = normalizedByteField(instance, "rotationCenterZ", 0.5f);

        // At the deterministic recording time (t=0), Create's rotating shader
        // still applies rotationOffset around rotationAxis after the base
        // orientation.  Capturing it makes static cog teeth and coupled shafts
        // agree with the actual renderer without introducing animation.
        float axisX = normalizedByteField(instance, "rotationAxisX", 0.0f);
        float axisY = normalizedByteField(instance, "rotationAxisY", 0.0f);
        float axisZ = normalizedByteField(instance, "rotationAxisZ", 0.0f);
        float axisLengthSquared = axisX * axisX + axisY * axisY + axisZ * axisZ;
        Quaternionf kineticRotation = null;
        if (axisLengthSquared > 0.000001f) {
            float inverseLength = (float) (1.0 / Math.sqrt(axisLengthSquared));
            float radians = (float) Math.toRadians(numberField(instance, "rotationOffset", 0.0f));
            kineticRotation = new Quaternionf().rotationAxis(
                radians,
                axisX * inverseLength,
                axisY * inverseLength,
                axisZ * inverseLength
            );
        }

        return centeredTransform(tx, ty, tz, pivotX, pivotY, pivotZ, kineticRotation, baseRotation);
    }

    private static Matrix4f centeredTransform(
        float tx,
        float ty,
        float tz,
        float pivotX,
        float pivotY,
        float pivotZ,
        org.joml.Quaternionfc outerRotation,
        org.joml.Quaternionfc innerRotation
    ) {
        Matrix4f transform = new Matrix4f().translation(
            tx + pivotX,
            ty + pivotY,
            tz + pivotZ
        );
        if (outerRotation != null) {
            transform.rotate(outerRotation);
        }
        if (innerRotation != null) {
            transform.rotate(innerRotation);
        }
        return transform.translate(-pivotX, -pivotY, -pivotZ);
    }

    private static boolean hasField(Object target, String name) {
        return target != null && findField(target.getClass(), name) != null;
    }

    private static float numberField(Object target, String name, float fallback) {
        Object value = readField(target, name);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static float normalizedByteField(Object target, String name, float fallback) {
        Object value = readField(target, name);
        if (!(value instanceof Number number)) {
            return fallback;
        }
        return Math.max(-1.0f, number.byteValue() / 127.0f);
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        java.lang.reflect.Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record TextureMapping(String texture, float u0, float u1, float v0, float v1, boolean atlasRecovered) {
        private float localU(float u) {
            return atlasRecovered ? localUv(u0, u1, u) : u;
        }

        private float localV(float v) {
            return atlasRecovered ? localUv(v0, v1, v) : v;
        }
    }

    private record AtlasSpriteInfo(
        net.minecraft.resources.ResourceLocation atlas,
        net.minecraft.resources.ResourceLocation name,
        String texture,
        int x,
        int y,
        int width,
        int height,
        int atlasWidth,
        int atlasHeight,
        float u0,
        float u1,
        float v0,
        float v1
    ) {
        private boolean contains(float minU, float maxU, float minV, float maxV, float epsilon) {
            return minU >= u0 - epsilon
                && maxU <= u1 + epsilon
                && minV >= v0 - epsilon
                && maxV <= v1 + epsilon;
        }

        private float area() {
            return Math.abs((u1 - u0) * (v1 - v0));
        }

        private boolean ownsUv(float u, float v) {
            if (!Float.isFinite(u) || !Float.isFinite(v) || atlasWidth <= 0 || atlasHeight <= 0) {
                return false;
            }
            int pixelX = Math.max(0, Math.min(atlasWidth - 1, (int) Math.floor(u * atlasWidth)));
            int pixelY = Math.max(0, Math.min(atlasHeight - 1, (int) Math.floor(v * atlasHeight)));
            return pixelX >= x && pixelX < x + width && pixelY >= y && pixelY < y + height;
        }

        private TextureMapping mapping() {
            return new TextureMapping(texture, u0, u1, v0, v1, true);
        }
    }

    private static final Map<net.minecraft.resources.ResourceLocation, List<AtlasSpriteInfo>> atlasSpriteCaches =
        new LinkedHashMap<>();

    private static TextureMapping textureMappingForPrimitive(
        dev.engine_room.flywheel.api.material.Material material,
        CapturedVertexList vertices,
        int start,
        int count,
        String textureNamespace
    ) {
        String fallback = textureForMaterial(material);
        if (!materialUsesAtlas(material)) {
            return new TextureMapping(fallback, 0f, 1f, 0f, 1f, false);
        }

        AtlasSpriteInfo sprite = recoverAtlasSprite(material.texture(), vertices, start, count);
        if (sprite == null) {
            return new TextureMapping(fallback, 0f, 1f, 0f, 1f, false);
        }
        return sprite.mapping();
    }

    private static TextureMapping textureMappingForPrimitive(
        dev.engine_room.flywheel.api.material.Material material,
        CapturedVertexList vertices,
        int[] indices,
        String textureNamespace
    ) {
        String fallback = textureForMaterial(material);
        if (!materialUsesAtlas(material)) {
            return new TextureMapping(fallback, 0f, 1f, 0f, 1f, false);
        }
        AtlasSpriteInfo sprite = recoverAtlasSprite(material.texture(), vertices, indices);
        if (sprite == null) {
            return new TextureMapping(fallback, 0f, 1f, 0f, 1f, false);
        }
        return sprite.mapping();
    }

    private static boolean materialUsesAtlas(dev.engine_room.flywheel.api.material.Material material) {
        if (material == null || material.texture() == null) {
            return false;
        }
        return material.texture().getPath().startsWith("textures/atlas/");
    }

    private static AtlasSpriteInfo recoverAtlasSprite(
        net.minecraft.resources.ResourceLocation atlasLocation,
        CapturedVertexList vertices,
        int start,
        int count
    ) {
        float minU = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int i = start; i < start + count; i++) {
            minU = Math.min(minU, vertices.u[i]);
            maxU = Math.max(maxU, vertices.u[i]);
            minV = Math.min(minV, vertices.v[i]);
            maxV = Math.max(maxV, vertices.v[i]);
        }
        if (!Float.isFinite(minU) || !Float.isFinite(maxU) || !Float.isFinite(minV) || !Float.isFinite(maxV)) {
            return null;
        }

        float centerU = (minU + maxU) * 0.5f;
        float centerV = (minV + maxV) * 0.5f;
        AtlasSpriteInfo owner = spriteOwningUv(atlasLocation, centerU, centerV);
        if (owner != null) {
            boolean allInsetVerticesOwned = true;
            for (int i = start; i < start + count; i++) {
                float insetU = vertices.u[i] * 0.999f + centerU * 0.001f;
                float insetV = vertices.v[i] * 0.999f + centerV * 0.001f;
                if (!owner.ownsUv(insetU, insetV)) {
                    allInsetVerticesOwned = false;
                    break;
                }
            }
            if (allInsetVerticesOwned) {
                return owner;
            }
        }

        AtlasSpriteInfo best = null;
        float bestScore = Float.POSITIVE_INFINITY;
        float epsilon = Float.parseFloat(System.getProperty("blockprint.baker.atlasSpriteUvEpsilon", "0.00075"));
        for (AtlasSpriteInfo candidate : atlasSprites(atlasLocation)) {
            if (!candidate.contains(minU, maxU, minV, maxV, epsilon)) {
                continue;
            }
            float candidateCenterU = (candidate.u0() + candidate.u1()) * 0.5f;
            float candidateCenterV = (candidate.v0() + candidate.v1()) * 0.5f;
            float centerDistance =
                Math.abs(centerU - candidateCenterU) +
                Math.abs(centerV - candidateCenterV);
            float score = candidate.area() + centerDistance * 0.0001f;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static AtlasSpriteInfo recoverAtlasSprite(
        net.minecraft.resources.ResourceLocation atlasLocation,
        CapturedVertexList vertices,
        int[] indices
    ) {
        float centerU = 0.0f;
        float centerV = 0.0f;
        for (int index : indices) {
            centerU += vertices.u[index];
            centerV += vertices.v[index];
        }
        centerU /= indices.length;
        centerV /= indices.length;

        AtlasSpriteInfo owner = spriteOwningUv(atlasLocation, centerU, centerV);
        if (owner != null) {
            boolean allOwned = true;
            for (int index : indices) {
                float insetU = vertices.u[index] * 0.999f + centerU * 0.001f;
                float insetV = vertices.v[index] * 0.999f + centerV * 0.001f;
                if (!owner.ownsUv(insetU, insetV)) {
                    allOwned = false;
                    break;
                }
            }
            if (allOwned) {
                return owner;
            }
        }

        return null;
    }

    private static AtlasSpriteInfo spriteOwningUv(
        net.minecraft.resources.ResourceLocation atlasLocation,
        float u,
        float v
    ) {
        AtlasSpriteInfo best = null;
        for (AtlasSpriteInfo candidate : atlasSprites(atlasLocation)) {
            if (!candidate.ownsUv(u, v)) {
                continue;
            }
            if (best == null || candidate.area() < best.area()) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<MeshAccumulator> resolveRecordedAtlasMesh(MeshAccumulator mesh) {
        if (!isAtlasTextureMarker(mesh.texture)) {
            return List.of(mesh);
        }
        net.minecraft.resources.ResourceLocation atlasLocation = atlasLocationFromMarker(mesh.texture);

        Map<String, MeshAccumulator> resolved = new LinkedHashMap<>();
        int unresolvedTriangles = 0;
        for (int i = 0; i + 2 < mesh.indices.size(); i += 3) {
            int i0 = mesh.indices.get(i);
            int i1 = mesh.indices.get(i + 1);
            int i2 = mesh.indices.get(i + 2);
            AtlasSpriteInfo sprite = recoverRecordedTriangleSprite(atlasLocation, mesh, i0, i1, i2);
            if (sprite == null) {
                reportAtlasDiagnostic(atlasLocation, mesh, i0, i1, i2);
                unresolvedTriangles++;
                continue;
            }
            MeshAccumulator target = resolved.computeIfAbsent(sprite.texture(), MeshAccumulator::new);
            target.addStoredTriangle(mesh, i0, i1, i2, sprite.mapping());
        }
        if (unresolvedTriangles > 0) {
            System.out.println(
                "[BlockPrintModelBaker] Atlas ownership unresolved for " + unresolvedTriangles +
                " triangles from " + mesh.texture + "; skipped without placeholder texture"
            );
        }
        return new ArrayList<>(resolved.values());
    }

    private static void reportAtlasDiagnostic(
        net.minecraft.resources.ResourceLocation atlasLocation,
        MeshAccumulator mesh,
        int i0,
        int i1,
        int i2
    ) {
        if (atlasDiagnosticSamples >= 12) {
            return;
        }
        atlasDiagnosticSamples++;
        int[] indices = {i0, i1, i2};
        StringBuilder sample = new StringBuilder("atlas=").append(atlasLocation).append('\t');
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) sample.append("; ");
            int uvOffset = indices[i] * 2;
            sample.append(formatFloat(mesh.uvs.get(uvOffset)))
                .append(',')
                .append(formatFloat(mesh.uvs.get(uvOffset + 1)));
        }
        float centerU = (mesh.uvs.get(i0 * 2) + mesh.uvs.get(i1 * 2) + mesh.uvs.get(i2 * 2)) / 3.0f;
        float centerV = (mesh.uvs.get(i0 * 2 + 1) + mesh.uvs.get(i1 * 2 + 1) + mesh.uvs.get(i2 * 2 + 1)) / 3.0f;
        AtlasSpriteInfo owner = spriteOwningUv(atlasLocation, centerU, centerV);
        if (owner != null) {
            sample.append("\towner=").append(owner.name())
                .append(" rect=").append(owner.x()).append(',').append(owner.y())
                .append(',').append(owner.width()).append(',').append(owner.height())
                .append(" size=").append(owner.atlasWidth()).append('x').append(owner.atlasHeight());
        } else {
            sample.append("\towner=<none>");
        }
        System.out.println("[BlockPrintModelBaker] Unresolved atlas UV sample: " + sample);
        atlasDiagnostics.add(sample.toString());
    }

    private static void writeBlockAtlasLayout(Path output) throws IOException {
        writeAtlasLayout(
            output,
            List.of(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS),
            false
        );
    }

    private static void writeAtlasLayouts(Path output) throws IOException {
        atlasSprites(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        writeAtlasLayout(output, new ArrayList<>(atlasSpriteCaches.keySet()), true);
    }

    private static void writeAtlasLayout(
        Path output,
        List<net.minecraft.resources.ResourceLocation> atlasLocations,
        boolean includeAtlasColumn
    ) throws IOException {
        StringBuilder text = new StringBuilder();
        if (includeAtlasColumn) {
            text.append("atlas\t");
        }
        text.append("name\tx\ty\twidth\theight\tatlasWidth\tatlasHeight\tu0\tu1\tv0\tv1\n");
        for (net.minecraft.resources.ResourceLocation atlasLocation : atlasLocations) {
            for (AtlasSpriteInfo sprite : atlasSprites(atlasLocation)) {
                if (includeAtlasColumn) {
                    text.append(sprite.atlas()).append('\t');
                }
                text.append(sprite.name()).append('\t')
                    .append(sprite.x()).append('\t').append(sprite.y()).append('\t')
                    .append(sprite.width()).append('\t').append(sprite.height()).append('\t')
                    .append(sprite.atlasWidth()).append('\t').append(sprite.atlasHeight()).append('\t')
                    .append(formatFloat(sprite.u0())).append('\t').append(formatFloat(sprite.u1())).append('\t')
                    .append(formatFloat(sprite.v0())).append('\t').append(formatFloat(sprite.v1())).append('\n');
            }
        }
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static AtlasSpriteInfo recoverRecordedTriangleSprite(
        net.minecraft.resources.ResourceLocation atlasLocation,
        MeshAccumulator mesh,
        int i0,
        int i1,
        int i2
    ) {
        int[] vertexIndices = {i0, i1, i2};
        float centerU = 0.0f;
        float centerV = 0.0f;
        for (int index : vertexIndices) {
            centerU += mesh.uvs.get(index * 2);
            centerV += mesh.uvs.get(index * 2 + 1);
        }
        centerU /= 3.0f;
        centerV /= 3.0f;

        AtlasSpriteInfo owner = spriteOwningUv(atlasLocation, centerU, centerV);
        if (owner != null) {
            boolean allOwned = true;
            for (int index : vertexIndices) {
                float u = mesh.uvs.get(index * 2);
                float v = mesh.uvs.get(index * 2 + 1);
                float insetU = u * 0.999f + centerU * 0.001f;
                float insetV = v * 0.999f + centerV * 0.001f;
                if (!owner.ownsUv(insetU, insetV)) {
                    allOwned = false;
                    break;
                }
            }
            if (allOwned) {
                return owner;
            }
        }

        return null;
    }

    private static List<AtlasSpriteInfo> atlasSprites(net.minecraft.resources.ResourceLocation atlasLocation) {
        if (atlasLocation == null) {
            return List.of();
        }
        List<AtlasSpriteInfo> cached = atlasSpriteCaches.get(atlasLocation);
        if (cached != null) {
            return cached;
        }

        List<AtlasSpriteInfo> sprites = new ArrayList<>();
        try {
            Object atlasObject = textureAtlas(atlasLocation);
            if (atlasObject instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas) {
                for (Object value : atlasSpriteValues(atlas)) {
                    if (value instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
                        net.minecraft.resources.ResourceLocation name = sprite.contents().name();
                        if (name == null || "missingno".equals(name.getPath())) {
                            continue;
                        }
                        int spriteWidth = sprite.contents().width();
                        int spriteHeight = sprite.contents().height();
                        int atlasWidth = Math.round(spriteWidth / Math.max(0.0000001f, sprite.getU1() - sprite.getU0()));
                        int atlasHeight = Math.round(spriteHeight / Math.max(0.0000001f, sprite.getV1() - sprite.getV0()));
                        sprites.add(new AtlasSpriteInfo(
                            atlasLocation,
                            name,
                            texturePath(name),
                            sprite.getX(),
                            sprite.getY(),
                            spriteWidth,
                            spriteHeight,
                            atlasWidth,
                            atlasHeight,
                            sprite.getU0(),
                            sprite.getU1(),
                            sprite.getV0(),
                            sprite.getV1()
                        ));
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Atlas sprite recovery unavailable for " + atlasLocation + ": " + t.getClass().getName() + ": " + t.getMessage());
        }
        sprites.sort((a, b) -> Float.compare(a.area(), b.area()));
        List<AtlasSpriteInfo> result = List.copyOf(sprites);
        atlasSpriteCaches.put(atlasLocation, result);
        System.out.println("[BlockPrintModelBaker] Atlas sprite recovery indexed " + sprites.size() + " sprites from " + atlasLocation);
        return result;
    }

    private static Object textureAtlas(net.minecraft.resources.ResourceLocation atlasLocation) {
        Object minecraft = net.minecraft.client.Minecraft.getInstance();
        Object modelManager = invokeNoArg(minecraft, "getModelManager");
        if (modelManager != null) {
            Object atlas = invokeOneArg(
                modelManager,
                "getAtlas",
                net.minecraft.resources.ResourceLocation.class,
                atlasLocation
            );
            if (atlas != null) {
                return atlas;
            }
        }
        Object textureManager = invokeNoArg(minecraft, "getTextureManager");
        if (textureManager != null) {
            Object atlas = invokeOneArg(
                textureManager,
                "getTexture",
                net.minecraft.resources.ResourceLocation.class,
                atlasLocation
            );
            if (atlas != null) {
                return atlas;
            }
        }
        return null;
    }

    private static List<Object> atlasSpriteValues(Object atlas) {
        List<Object> sprites = new ArrayList<>();
        if (atlas instanceof net.minecraft.client.renderer.texture.TextureAtlas textureAtlas) {
            sprites.addAll(textureAtlas.getTextures().values());
            if (!sprites.isEmpty()) {
                return sprites;
            }
        }
        Class<?> type = atlas.getClass();
        while (type != null) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(atlas);
                    if (!(value instanceof Map<?, ?> map)) {
                        continue;
                    }
                    int before = sprites.size();
                    for (Object sprite : map.values()) {
                        if (sprite instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite) {
                            sprites.add(sprite);
                        }
                    }
                    if (sprites.size() > before) {
                        return sprites;
                    }
                } catch (Throwable ignored) {
                    // Try the next map field. Field names vary across mappings/versions.
                }
            }
            type = type.getSuperclass();
        }
        return sprites;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Object invokeOneArg(Object target, String name, Class<?> parameterType, Object argument) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                    continue;
                }
                if (!method.getParameterTypes()[0].isAssignableFrom(parameterType)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target, argument);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static String textureForMaterial(dev.engine_room.flywheel.api.material.Material material) {
        String fallback = System.getProperty("blockprint.baker.rendererFallbackTexture", "minecraft:textures/block/white_concrete");
        if (material == null || material.texture() == null) {
            return fallback;
        }
        net.minecraft.resources.ResourceLocation location = material.texture();
        String path = location.getPath();
        if (path.startsWith("textures/atlas/")) {
            return atlasTextureMarker(location);
        }
        if (path.startsWith("textures/")) {
            path = path.substring("textures/".length());
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        return location.getNamespace() + ":textures/" + path;
    }

    private static final class CapturedVertexList implements dev.engine_room.flywheel.api.vertex.MutableVertexList {
        private final int vertexCount;
        private final float[] x;
        private final float[] y;
        private final float[] z;
        private final float[] r;
        private final float[] g;
        private final float[] b;
        private final float[] a;
        private final float[] u;
        private final float[] v;
        private final int[] overlay;
        private final int[] light;
        private final float[] nx;
        private final float[] ny;
        private final float[] nz;

        private CapturedVertexList(int vertexCount) {
            this.vertexCount = vertexCount;
            this.x = new float[vertexCount];
            this.y = new float[vertexCount];
            this.z = new float[vertexCount];
            this.r = new float[vertexCount];
            this.g = new float[vertexCount];
            this.b = new float[vertexCount];
            this.a = new float[vertexCount];
            this.u = new float[vertexCount];
            this.v = new float[vertexCount];
            this.overlay = new int[vertexCount];
            this.light = new int[vertexCount];
            this.nx = new float[vertexCount];
            this.ny = new float[vertexCount];
            this.nz = new float[vertexCount];
            Arrays.fill(this.a, 1.0f);
            Arrays.fill(this.ny, 1.0f);
        }

        @Override public float x(int index) { return x[index]; }
        @Override public float y(int index) { return y[index]; }
        @Override public float z(int index) { return z[index]; }
        @Override public float r(int index) { return r[index]; }
        @Override public float g(int index) { return g[index]; }
        @Override public float b(int index) { return b[index]; }
        @Override public float a(int index) { return a[index]; }
        @Override public float u(int index) { return u[index]; }
        @Override public float v(int index) { return v[index]; }
        @Override public int overlay(int index) { return overlay[index]; }
        @Override public int light(int index) { return light[index]; }
        @Override public float normalX(int index) { return nx[index]; }
        @Override public float normalY(int index) { return ny[index]; }
        @Override public float normalZ(int index) { return nz[index]; }
        @Override public int vertexCount() { return vertexCount; }

        @Override public void x(int index, float value) { x[index] = value; }
        @Override public void y(int index, float value) { y[index] = value; }
        @Override public void z(int index, float value) { z[index] = value; }
        @Override public void r(int index, float value) { r[index] = value; }
        @Override public void g(int index, float value) { g[index] = value; }
        @Override public void b(int index, float value) { b[index] = value; }
        @Override public void a(int index, float value) { a[index] = value; }
        @Override public void u(int index, float value) { u[index] = value; }
        @Override public void v(int index, float value) { v[index] = value; }
        @Override public void overlay(int index, int value) { overlay[index] = value; }
        @Override public void light(int index, int value) { light[index] = value; }
        @Override public void normalX(int index, float value) { nx[index] = value; }
        @Override public void normalY(int index, float value) { ny[index] = value; }
        @Override public void normalZ(int index, float value) { nz[index] = value; }
    }

    private static boolean bootstrapMinecraft() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            System.out.println("[BlockPrintModelBaker] SharedConstants.tryDetectVersion(): OK, version=" + net.minecraft.SharedConstants.getCurrentVersion().getName());
            net.minecraft.server.Bootstrap.bootStrap();
            System.out.println("[BlockPrintModelBaker] Bootstrap.bootStrap(): OK");
            net.minecraft.server.Bootstrap.validate();
            System.out.println("[BlockPrintModelBaker] Bootstrap.validate(): OK");
            System.out.println("[BlockPrintModelBaker] BLOCK registry size=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet().size());
            return true;
        } catch (Throwable t) {
            System.out.println("[BlockPrintModelBaker] Minecraft bootstrap failed: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            return false;
        }
    }

    private static void reportBlock(String id) {
        net.minecraft.resources.ResourceLocation location = net.minecraft.resources.ResourceLocation.parse(id);
        boolean present = net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(location);
        System.out.println("[BlockPrintModelBaker] BLOCK " + id + " present=" + present);
        if (present) {
            net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(location);
            System.out.println("[BlockPrintModelBaker]   class=" + block.getClass().getName());
            System.out.println("[BlockPrintModelBaker]   states=" + block.getStateDefinition().getPossibleStates().size());
        }
    }
}
