package io.github.moxisuki.blockprint.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

/**
 * A deterministic, read-mostly client Level used only by the build-time
 * recorder.
 *
 * <p>The Minecraft Level constructor requires a complete server registry set
 * solely to construct DamageSources. A model recorder running at the title
 * screen intentionally has no server connection or dynamic registries, so the
 * instance is allocated without invoking that constructor and every world
 * service exposed to renderers is implemented by this class. This remains
 * isolated to the temporary NeoForge tool and is never packaged into core.</p>
 */
final class CaptureLevel extends Level {
    private Map<BlockPos, BlockState> states;
    private Map<BlockPos, BlockEntity> blockEntities;
    private RegistryAccess registryAccess;
    private FixedLevelData fixedLevelData;
    private TickRateManager tickRateManager;
    private Scoreboard scoreboard;
    private RecipeManager recipeManager;
    private RandomSource captureRandom;
    private LevelEntityGetter<Entity> entityGetter;
    private ChunkSource chunkSource;
    private Holder<DimensionType> captureDimensionType;
    private WorldBorder captureWorldBorder;

    private CaptureLevel() {
        super(
            null,
            Level.OVERWORLD,
            RegistryAccess.EMPTY,
            null,
            () -> InactiveProfiler.INSTANCE,
            true,
            false,
            0L,
            1_000_000
        );
        throw new AssertionError("CaptureLevel must be allocated without invoking Level's server constructor");
    }

    static CaptureLevel create(BlockState state) {
        try {
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field = unsafeType.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
            CaptureLevel level = (CaptureLevel) allocateInstance.invoke(unsafe, CaptureLevel.class);
            level.initialize(state);
            return level;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to allocate deterministic CaptureLevel", e);
        }
    }

    private void initialize(BlockState state) {
        states = new HashMap<>();
        blockEntities = new HashMap<>();
        registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        fixedLevelData = new FixedLevelData();
        tickRateManager = new TickRateManager();
        scoreboard = new Scoreboard();
        recipeManager = new RecipeManager(registryAccess);
        captureRandom = RandomSource.create(42L);
        entityGetter = new EmptyEntityGetter();
        chunkSource = new EmptyChunkSource();
        captureDimensionType = Holder.direct(new DimensionType(
            OptionalLong.of(0L),
            true,
            false,
            false,
            true,
            1.0,
            true,
            false,
            -64,
            384,
            384,
            net.minecraft.tags.BlockTags.INFINIBURN_OVERWORLD,
            BuiltinDimensionTypes.OVERWORLD_EFFECTS,
            1.0f,
            new DimensionType.MonsterSettings(
                false,
                true,
                net.minecraft.util.valueproviders.UniformInt.of(0, 7),
                0
            )
        ));
        captureWorldBorder = new WorldBorder();
        states.put(BlockPos.ZERO, state);

        if (state.getBlock() instanceof EntityBlock entityBlock) {
            try {
                BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, state);
                if (blockEntity != null) {
                    blockEntity.setBlockState(state);
                    blockEntity.setLevel(this);
                    blockEntities.put(BlockPos.ZERO, blockEntity);
                }
            } catch (Throwable failure) {
                // Some mods read a server config from their BlockEntity constructor,
                // even on the client title screen where that config is intentionally
                // not loaded. The block model can still be recorded; BER/Visual
                // capture for this state will be reported as absent instead of
                // aborting the entire catalog export.
                System.out.println(
                    "[BlockPrintModelBaker] Default BlockEntity unavailable for " + state + ": " +
                    failure.getClass().getName() + ": " + failure.getMessage()
                );
            }
        }
    }

    BlockEntity defaultBlockEntity() {
        return blockEntities.get(BlockPos.ZERO);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return states.getOrDefault(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        BlockState state = states.get(pos);
        return state == null ? Fluids.EMPTY.defaultFluidState() : state.getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    @Override
    public long getGameTime() {
        return 0L;
    }

    @Override
    public long getDayTime() {
        return 0L;
    }

    @Override
    public boolean isClientSide() {
        return true;
    }

    @Override
    public RegistryAccess registryAccess() {
        return registryAccess;
    }

    @Override
    public net.minecraft.resources.ResourceKey<Level> dimension() {
        return Level.OVERWORLD;
    }

    @Override
    public DimensionType dimensionType() {
        return captureDimensionType.value();
    }

    @Override
    public Holder<DimensionType> dimensionTypeRegistration() {
        return captureDimensionType;
    }

    @Override
    public WorldBorder getWorldBorder() {
        return captureWorldBorder;
    }

    @Override
    public net.minecraft.util.profiling.ProfilerFiller getProfiler() {
        return InactiveProfiler.INSTANCE;
    }

    @Override
    public java.util.function.Supplier<net.minecraft.util.profiling.ProfilerFiller> getProfilerSupplier() {
        return () -> InactiveProfiler.INSTANCE;
    }

    @Override
    public GameRules getGameRules() {
        return fixedLevelData.getGameRules();
    }

    @Override
    public LevelData getLevelData() {
        return fixedLevelData;
    }

    @Override
    public RandomSource getRandom() {
        return captureRandom;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }

    @Override
    public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return true;
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shade) {
        return 1.0f;
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        return 0xFFFFFFFF;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return null;
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(
        net.minecraft.world.entity.player.Player player,
        double x,
        double y,
        double z,
        Holder<net.minecraft.sounds.SoundEvent> sound,
        SoundSource source,
        float volume,
        float pitch,
        long seed
    ) {
    }

    @Override
    public void playSeededSound(
        net.minecraft.world.entity.player.Player player,
        Entity entity,
        Holder<net.minecraft.sounds.SoundEvent> sound,
        SoundSource source,
        float volume,
        float pitch,
        long seed
    ) {
    }

    @Override
    public String gatherChunkSourceStats() {
        return "BlockPrint CaptureLevel";
    }

    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public List<? extends net.minecraft.world.entity.player.Player> players() {
        return List.of();
    }

    @Override
    public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
        return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return null;
    }

    @Override
    public TickRateManager tickRateManager() {
        return tickRateManager;
    }

    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
    }

    @Override
    public MapId getFreeMapId() {
        return new MapId(0);
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return entityGetter;
    }

    @Override
    public PotionBrewing potionBrewing() {
        return PotionBrewing.EMPTY;
    }

    @Override
    public void setDayTimeFraction(float fraction) {
    }

    @Override
    public float getDayTimeFraction() {
        return 0.0f;
    }

    @Override
    public float getDayTimePerTick() {
        return 0.0f;
    }

    @Override
    public void setDayTimePerTick(float value) {
    }

    @Override
    public void levelEvent(net.minecraft.world.entity.player.Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
    }

    @Override
    public void close() {
    }

    private static final class FixedLevelData implements WritableLevelData {
        private final GameRules gameRules = new GameRules();

        @Override public BlockPos getSpawnPos() { return BlockPos.ZERO; }
        @Override public float getSpawnAngle() { return 0.0f; }
        @Override public long getGameTime() { return 0L; }
        @Override public long getDayTime() { return 0L; }
        @Override public boolean isThundering() { return false; }
        @Override public boolean isRaining() { return false; }
        @Override public void setRaining(boolean raining) { }
        @Override public boolean isHardcore() { return false; }
        @Override public GameRules getGameRules() { return gameRules; }
        @Override public Difficulty getDifficulty() { return Difficulty.NORMAL; }
        @Override public boolean isDifficultyLocked() { return true; }
        @Override public void setSpawn(BlockPos pos, float angle) { }
    }

    private static final class EmptyEntityGetter implements LevelEntityGetter<Entity> {
        @Override public Entity get(int id) { return null; }
        @Override public Entity get(UUID uuid) { return null; }
        @Override public Iterable<Entity> getAll() { return List.of(); }
        @Override public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) { }
        @Override public void get(AABB bounds, Consumer<Entity> consumer) { }
        @Override public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) { }
    }

    private final class EmptyChunkSource extends ChunkSource {
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) { return null; }
        @Override public void tick(BooleanSupplier hasTimeLeft, boolean tickChunks) { }
        @Override public String gatherStats() { return "BlockPrint CaptureLevel"; }
        @Override public int getLoadedChunksCount() { return 0; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public net.minecraft.world.level.BlockGetter getLevel() { return CaptureLevel.this; }
    }
}
