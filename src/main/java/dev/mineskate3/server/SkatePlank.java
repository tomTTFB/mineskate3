package dev.mineskate3.server;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.mineskate3.MineSkate3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;

/**
 * The board under a skater, for players who do not have the mod. It is an
 * ordinary vanilla block display of planks that follows the skater's feet, so
 * a stock client draws it with no help. Clients with the mod never see it:
 * they get the real skater and board over the mod's own packets instead.
 */
final class SkatePlank extends Display.BlockDisplay {
    static final String TAG = "mineskate3_plank";

    /** A 0.4 x 0.1 x 1.2 slab centred on the entity, which sits at the skater's feet. */
    private static final String SNBT = "{"
            + "Pos:[0d,0d,0d],Motion:[0d,0d,0d],Rotation:[0f,0f],"
            + "block_state:{Name:\"minecraft:oak_planks\"},"
            + "teleport_duration:3,"
            + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
            + "scale:[0.4f,0.1f,1.2f],translation:[-0.2f,0f,-0.6f]}"
            + "}";

    SkatePlank(ServerLevel level) {
        super(EntityType.BLOCK_DISPLAY, level);
        try {
            load(TagParser.parseTag(SNBT));
        } catch (CommandSyntaxException e) {
            MineSkate3.LOGGER.error("Bad skate plank definition", e);
        }
        addTag(TAG);
        setNoGravity(true);
    }

    /** Only players without the mod are shown the plank. */
    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return !SkateServer.hasMod(player);
    }

    /** It is rebuilt from the skater every tick; never write it to the world. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    void follow(ServerPlayer skater) {
        moveTo(skater.getX(), skater.getY(), skater.getZ(), skater.getYRot(), 0);
    }
}
