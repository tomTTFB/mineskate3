package dev.mineskate3.block;

import dev.mineskate3.MineSkate3;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Skate park blocks: grind rails and ramps, in their own creative tab. */
public final class SkateBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MineSkate3.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MineSkate3.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MineSkate3.MODID);

    public static final DeferredBlock<GrindRailBlock> GRIND_RAIL = block("grind_rail",
            () -> new GrindRailBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2f, 6f)
                    .sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredBlock<RampBlock> RAMP = ramp("ramp", RampBlock.Profile.RAMP);
    public static final DeferredBlock<RampBlock> LONG_RAMP_LOW = ramp("long_ramp_low", RampBlock.Profile.LONG_RAMP_LOW);
    public static final DeferredBlock<RampBlock> LONG_RAMP_HIGH =
            ramp("long_ramp_high", RampBlock.Profile.LONG_RAMP_HIGH);
    public static final DeferredBlock<RampBlock> QUARTER_PIPE = ramp("quarter_pipe", RampBlock.Profile.QUARTER_PIPE);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("skate_park",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.mineskate3.skate_park"))
                    .icon(() -> QUARTER_PIPE.toStack())
                    .displayItems((parameters, output) -> {
                        output.accept(GRIND_RAIL);
                        output.accept(RAMP);
                        output.accept(LONG_RAMP_LOW);
                        output.accept(LONG_RAMP_HIGH);
                        output.accept(QUARTER_PIPE);
                    })
                    .build());

    private SkateBlocks() {}

    private static DeferredBlock<RampBlock> ramp(String name, RampBlock.Profile profile) {
        return block(name, () -> new RampBlock(profile, BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
                .strength(1.5f, 3f).sound(SoundType.WOOD).noOcclusion()));
    }

    private static <B extends Block> DeferredBlock<B> block(String name, Supplier<B> factory) {
        DeferredBlock<B> block = BLOCKS.register(name, factory);
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
    }
}
