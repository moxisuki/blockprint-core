package io.github.moxisuki.blockprint.core.glb.model.create

internal data class CreateStaticAssembly(
    val blockName: String,
    val blockEntity: String,
    val renderer: String?,
    val visual: String?,
    val parts: List<CreateStaticAssemblyPart>,
)

internal data class CreateStaticAssemblyPart(
    val model: String,
    val transform: CreateStaticPartTransform,
    val source: String,
    val properties: Map<String, String> = emptyMap(),
    val selector: CreateStaticPartSelector = CreateStaticPartSelector.FIXED,
)

internal enum class CreateStaticPartTransform {
    /** Model coordinates are already in block/world orientation. */
    WORLD,

    /** Apply the same blockstate rotation as the base model. */
    BASE_BLOCKSTATE,

    /** Model's natural forward direction is +Z/south; rotate it to the block `facing`. */
    FACING_FROM_SOUTH,

    /** Same as [FACING_FROM_SOUTH], but uses the opposite of the block `facing`. */
    FACING_OPPOSITE_FROM_SOUTH,

    /** Deployer moving pole: follows `facing`; the static preview keeps animation offset at rest. */
    DEPLOYER_POLE,

    /** Deployer hand: follows `facing`; the static preview keeps animation offset at rest. */
    DEPLOYER_HAND,

    /** A standard Create kinetic shaft: default axis is Y, rotate to the block's kinetic axis. */
    KINETIC_SHAFT_AXIS,

    /** Steam engine moving partials in a deterministic static preview pose. */
    STEAM_ENGINE,

    /** Mechanical arm dynamic partials in a deterministic static preview pose. */
    MECHANICAL_ARM,
}

internal enum class CreateStaticPartSelector {
    /** Resolve [CreateStaticAssemblyPart.model] directly. */
    FIXED,

    /** Build a Create belt loop from `slope` + `part` blockstate properties. */
    BELT_LOOP,

    /** Add a belt pulley for `part=start|end|pulley`. */
    BELT_PULLEY,

    /** Add one standard shaft along the block `axis`. */
    SHAFT_AXIS,

    /** Add the two perpendicular internal shafts of a gearbox. */
    GEARBOX_SHAFTS,

    /** Add a shaftless cogwheel plus optional top/bottom cogwheel shafts. */
    ENCASED_COGWHEEL,

    /** Add a shaftless large cogwheel plus optional top/bottom cogwheel shafts. */
    ENCASED_LARGE_COGWHEEL,

    /** Add the four static funnel curtain flaps. */
    FUNNEL_FLAPS,

    /** Add the static belt-tunnel curtain flaps for the tunnel's open sides. */
    BELT_TUNNEL_FLAPS,

    /** Add the static piston/linkage/connector assembly for steam engines. */
    STEAM_ENGINE,

    /** Add the full static upper assembly for mechanical arms. */
    MECHANICAL_ARM,

    /** Add static pipe arms / junction casing normally supplied by PipeAttachmentModel. */
    FLUID_PIPE_ATTACHMENTS,
}

internal object CreateStaticAssemblyManifest {
    /**
     * Static block-entity assemblies extracted from Create source anchors:
     * AllBlockEntityTypes + AllPartialModels + renderer / visual sources.
     *
     * The transforms are intentionally semantic rather than block-name based:
     * - Oriented/partial-facing render calls become [CreateStaticPartTransform.FACING_FROM_SOUTH].
     * - Plain partial render calls stay [CreateStaticPartTransform.WORLD].
     * - Renderer-provided `shaft(getRotationAxisOf(...))` becomes
     *   [CreateStaticPartTransform.KINETIC_SHAFT_AXIS].
     */
    val byBlockName: Map<String, CreateStaticAssembly> = listOf(
        CreateStaticAssembly(
            blockName = "mechanical_arm",
            blockEntity = "create:mechanical_arm",
            renderer = "ArmRenderer",
            visual = "ArmVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/mechanical_arm/item",
                    transform = CreateStaticPartTransform.MECHANICAL_ARM,
                    source = "AllPartialModels.ARM_* via mechanical_arm/item static composition",
                    selector = CreateStaticPartSelector.MECHANICAL_ARM,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "fluid_pipe",
            blockEntity = "create:fluid_pipe",
            renderer = null,
            visual = "PipeAttachmentModel::withAO",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/fluid_pipe/connection",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "PipeAttachmentModel + AllPartialModels.PIPE_ATTACHMENTS",
                    selector = CreateStaticPartSelector.FLUID_PIPE_ATTACHMENTS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "chain_conveyor",
            blockEntity = "create:chain_conveyor",
            renderer = "ChainConveyorRenderer",
            visual = "ChainConveyorVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/chain_conveyor/shaft",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.CHAIN_CONVEYOR_SHAFT",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/chain_conveyor/wheel",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.CHAIN_CONVEYOR_WHEEL",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "steam_engine",
            blockEntity = "create:steam_engine",
            renderer = "SteamEngineRenderer",
            visual = "SteamEngineVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/steam_engine",
                    transform = CreateStaticPartTransform.STEAM_ENGINE,
                    source = "AllPartialModels.ENGINE_PISTON / ENGINE_LINKAGE / ENGINE_CONNECTOR",
                    selector = CreateStaticPartSelector.STEAM_ENGINE,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "belt",
            blockEntity = "create:belt",
            renderer = "BeltRenderer",
            visual = "BeltVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/belt",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Belt model selected by slope/part",
                    selector = CreateStaticPartSelector.BELT_LOOP,
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/belt_pulley",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Belt pulley for start/end/pulley segments",
                    selector = CreateStaticPartSelector.BELT_PULLEY,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "andesite_funnel",
            blockEntity = "create:funnel",
            renderer = "FunnelRenderer",
            visual = "FunnelVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/funnel/flap",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Funnel flap curtain",
                    selector = CreateStaticPartSelector.FUNNEL_FLAPS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "brass_funnel",
            blockEntity = "create:funnel",
            renderer = "FunnelRenderer",
            visual = "FunnelVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/funnel/flap",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Funnel flap curtain",
                    selector = CreateStaticPartSelector.FUNNEL_FLAPS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "andesite_belt_funnel",
            blockEntity = "create:belt_funnel",
            renderer = "FunnelRenderer",
            visual = "FunnelVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/belt_funnel/flap",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Belt funnel flap curtain",
                    selector = CreateStaticPartSelector.FUNNEL_FLAPS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "brass_belt_funnel",
            blockEntity = "create:belt_funnel",
            renderer = "FunnelRenderer",
            visual = "FunnelVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/belt_funnel/flap",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Belt funnel flap curtain",
                    selector = CreateStaticPartSelector.FUNNEL_FLAPS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "andesite_tunnel",
            blockEntity = "create:andesite_tunnel",
            renderer = "BeltTunnelRenderer",
            visual = "BeltTunnelVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/belt_tunnel/flap",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.BELT_TUNNEL_FLAP",
                    selector = CreateStaticPartSelector.BELT_TUNNEL_FLAPS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "brass_tunnel",
            blockEntity = "create:brass_tunnel",
            renderer = "BeltTunnelRenderer",
            visual = "BeltTunnelVisual",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/belt_tunnel/flap",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.BELT_TUNNEL_FLAP",
                    selector = CreateStaticPartSelector.BELT_TUNNEL_FLAPS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "gearbox",
            blockEntity = "create:gearbox",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "Gearbox internal perpendicular shafts",
                    selector = CreateStaticPartSelector.GEARBOX_SHAFTS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "andesite_encased_shaft",
            blockEntity = "create:encased_shaft",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Encased shaft visible core",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "brass_encased_shaft",
            blockEntity = "create:encased_shaft",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Encased shaft visible core",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "metal_girder_encased_shaft",
            blockEntity = "create:encased_shaft",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Encased shaft visible core",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "clutch",
            blockEntity = "create:clutch",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Transmission block visible shaft",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "gearshift",
            blockEntity = "create:gearshift",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Transmission block visible shaft",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "sequenced_gearshift",
            blockEntity = "create:sequenced_gearshift",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Transmission block visible shaft",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "encased_chain_drive",
            blockEntity = "create:encased_chain_drive",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "Transmission block visible shaft",
                    selector = CreateStaticPartSelector.SHAFT_AXIS,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "andesite_encased_cogwheel",
            blockEntity = "create:encased_cogwheel",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/cogwheel_shaftless",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Encased cogwheel visible core",
                    selector = CreateStaticPartSelector.ENCASED_COGWHEEL,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "brass_encased_cogwheel",
            blockEntity = "create:encased_cogwheel",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/cogwheel_shaftless",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Encased cogwheel visible core",
                    selector = CreateStaticPartSelector.ENCASED_COGWHEEL,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "andesite_encased_large_cogwheel",
            blockEntity = "create:encased_large_cogwheel",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/large_cogwheel_shaftless",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Encased large cogwheel visible core",
                    selector = CreateStaticPartSelector.ENCASED_LARGE_COGWHEEL,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "brass_encased_large_cogwheel",
            blockEntity = "create:encased_large_cogwheel",
            renderer = null,
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/large_cogwheel_shaftless",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "Encased large cogwheel visible core",
                    selector = CreateStaticPartSelector.ENCASED_LARGE_COGWHEEL,
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "mechanical_drill",
            blockEntity = "create:drill",
            renderer = "DrillRenderer",
            visual = "OrientedRotatingVisual.of(AllPartialModels.DRILL_HEAD)",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/mechanical_drill/head",
                    transform = CreateStaticPartTransform.FACING_FROM_SOUTH,
                    source = "AllPartialModels.DRILL_HEAD",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "mechanical_press",
            blockEntity = "create:mechanical_press",
            renderer = "MechanicalPressRenderer",
            visual = "PressVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/mechanical_press/head",
                    transform = CreateStaticPartTransform.FACING_FROM_SOUTH,
                    source = "AllPartialModels.MECHANICAL_PRESS_HEAD",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "KineticBlockEntityRenderer.shaft(getRotationAxisOf)",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "mechanical_pump",
            blockEntity = "create:mechanical_pump",
            renderer = "PumpRenderer",
            visual = "SingleAxisRotatingVisual.ofZ(AllPartialModels.MECHANICAL_PUMP_COG)",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/mechanical_pump/cog",
                    transform = CreateStaticPartTransform.FACING_FROM_SOUTH,
                    source = "AllPartialModels.MECHANICAL_PUMP_COG",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "water_wheel",
            blockEntity = "create:water_wheel",
            renderer = "WaterWheelRenderer::standard",
            visual = "WaterWheelVisual::standard",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/water_wheel/wheel",
                    transform = CreateStaticPartTransform.BASE_BLOCKSTATE,
                    source = "AllPartialModels.WATER_WHEEL",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "large_water_wheel",
            blockEntity = "create:large_water_wheel",
            renderer = "WaterWheelRenderer::large",
            visual = "WaterWheelVisual::large",
            parts = emptyList(),
        ),
        CreateStaticAssembly(
            blockName = "crushing_wheel",
            blockEntity = "create:crushing_wheel",
            renderer = "KineticBlockEntityRenderer",
            visual = "SingleAxisRotatingVisual.of(AllPartialModels.CRUSHING_WHEEL)",
            parts = emptyList(),
        ),
        CreateStaticAssembly(
            blockName = "encased_fan",
            blockEntity = "create:encased_fan",
            renderer = "EncasedFanRenderer",
            visual = "FanVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft_half",
                    transform = CreateStaticPartTransform.FACING_OPPOSITE_FROM_SOUTH,
                    source = "AllPartialModels.SHAFT_HALF",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/encased_fan/propeller",
                    transform = CreateStaticPartTransform.FACING_OPPOSITE_FROM_SOUTH,
                    source = "AllPartialModels.ENCASED_FAN_INNER",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "deployer",
            blockEntity = "create:deployer",
            renderer = "DeployerRenderer",
            visual = "DeployerVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/shaft",
                    transform = CreateStaticPartTransform.KINETIC_SHAFT_AXIS,
                    source = "KineticBlockEntityRenderer.shaft(getRotationAxisOf)",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/deployer/pole",
                    transform = CreateStaticPartTransform.DEPLOYER_POLE,
                    source = "AllPartialModels.DEPLOYER_POLE",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/deployer/hand_pointing",
                    transform = CreateStaticPartTransform.DEPLOYER_HAND,
                    source = "AllPartialModels.DEPLOYER_HAND_POINTING",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "packager",
            blockEntity = "create:packager",
            renderer = "PackagerRenderer",
            visual = "PackagerVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/packager/hatch_closed",
                    transform = CreateStaticPartTransform.FACING_OPPOSITE_FROM_SOUTH,
                    source = "AllPartialModels.PACKAGER_HATCH_CLOSED",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/packager/tray",
                    transform = CreateStaticPartTransform.FACING_OPPOSITE_FROM_SOUTH,
                    source = "AllPartialModels.PACKAGER_TRAY_REGULAR",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "package_frogport",
            blockEntity = "create:package_frogport",
            renderer = "FrogportRenderer",
            visual = "FrogportVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/package_frogport/body",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.FROGPORT_BODY",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/package_frogport/head",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.FROGPORT_HEAD",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "stock_link",
            blockEntity = "create:stock_link",
            renderer = "LinkBulbRenderer",
            visual = null,
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/display_link/tube",
                    transform = CreateStaticPartTransform.FACING_FROM_SOUTH,
                    source = "AllPartialModels.DISPLAY_LINK_TUBE",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/display_link/glow",
                    transform = CreateStaticPartTransform.FACING_FROM_SOUTH,
                    source = "AllPartialModels.DISPLAY_LINK_GLOW",
                ),
            ),
        ),
        CreateStaticAssembly(
            blockName = "mechanical_mixer",
            blockEntity = "create:mechanical_mixer",
            renderer = "MechanicalMixerRenderer",
            visual = "MixerVisual::new",
            parts = listOf(
                CreateStaticAssemblyPart(
                    model = "create:block/cogwheel_shaftless",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.SHAFTLESS_COGWHEEL",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/mechanical_mixer/pole",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.MECHANICAL_MIXER_POLE",
                ),
                CreateStaticAssemblyPart(
                    model = "create:block/mechanical_mixer/head",
                    transform = CreateStaticPartTransform.WORLD,
                    source = "AllPartialModels.MECHANICAL_MIXER_HEAD",
                ),
            ),
        ),
    ).associateBy { it.blockName }
}
