package dev.mineskate3.client;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * What the block under the board does to the ride. Skate's collision has one
 * material, so the engine applies these on top of its own physics after each
 * tick while the wheels are down (see bridge::Surface in skate-host):
 * ice and anything slippery hands back speed Skate would have lost, soul sand,
 * honey and loose ground drag, and slime bounces the board on landing.
 */
public final class Surfaces {
    /** `drag` per second, `glide` and `bounce` as shares (0 to under 1). */
    public record Surface(float drag, float glide, float bounce) {
        public static final Surface NORMAL = new Surface(0f, 0f, 0f);
    }

    private static final float LOOSE_DRAG = 0.9f;
    private static final float SOFT_DRAG = 0.6f;

    private Surfaces() {}

    /** The surface under a board resting at `x, y, z` (its wheels' contact height). */
    public static Surface under(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(x, y - 0.05, z).mutable();
        // Wheels sit on top of the block below, or inside a shallow one
        // (snow layers, carpet, soul sand's lowered top).
        for (int i = 0; i < 2; i++, pos.move(0, -1, 0)) {
            BlockState state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty()) {
                return of(state, state.getFriction(level, pos, null));
            }
        }
        return Surface.NORMAL;
    }

    /** `friction` is Minecraft's slipperiness: 0.6 for most blocks, 0.98 for ice. */
    public static Surface of(BlockState state, float friction) {
        if (state.getBlock() instanceof SlimeBlock) {
            return new Surface(0f, 0f, 0.6f);
        }
        // Ice (0.98) keeps 80% of what Skate would shed, blue ice (0.989) a bit more.
        float glide = clamp((friction - 0.9f) * 10f, 0f, 0.9f);
        // Soul sand and honey halve walking speed; on a board they bog you down.
        float drag = (1f - clamp(state.getBlock().getSpeedFactor(), 0f, 1f)) * 2.5f;
        if (state.is(BlockTags.SAND) || state.is(BlockTags.SNOW) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SUSPICIOUS_GRAVEL) || state.is(Blocks.SOUL_SOIL)) {
            drag += LOOSE_DRAG;
        } else if (state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(BlockTags.WOOL)
                || state.is(BlockTags.WOOL_CARPETS)) {
            drag += SOFT_DRAG;
        }
        return drag == 0f && glide == 0f ? Surface.NORMAL : new Surface(drag, glide, 0f);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
