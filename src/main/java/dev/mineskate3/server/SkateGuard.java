package dev.mineskate3.server;

import dev.mineskate3.MineSkate3;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Skaters are moved by Skate's physics on their own client, so the server
 * waives its own movement corrections for them (see SkateServer). That waiver
 * would let a modified client walk through walls or fly by claiming to skate;
 * this checks every skater's movement each tick instead, using the player's
 * body rather than the feet, which Skate keeps on edges and rails Minecraft's
 * boxes disagree about. A skater who keeps failing is taken off the board and
 * put back where they last moved cleanly.
 */
public final class SkateGuard {
    /** Body sample heights above the feet: clear of fence and wall tops a skater grinds. */
    private static final double[] BODY = {0.7, 1.4};
    private static final double PATH_STEP = 0.2;

    private static final class Skater {
        final MoveCheck check = new MoveCheck();
        Vec3 last;
        Vec3 good;
    }

    private static final Map<UUID, Skater> SKATERS = new ConcurrentHashMap<>();

    private SkateGuard() {}

    static void started(ServerPlayer player) {
        Skater s = new Skater();
        s.last = s.good = player.position();
        SKATERS.put(player.getUUID(), s);
    }

    static void stopped(ServerPlayer player) {
        SKATERS.remove(player.getUUID());
    }

    /** The server moved the player itself: start measuring from there. */
    public static void teleported(ServerPlayer player) {
        Skater s = SKATERS.get(player.getUUID());
        if (s != null) {
            s.last = null;
        }
    }

    /** After the player's server tick. Returns why skating must stop, or null. */
    static String tick(ServerPlayer player) {
        Skater s = SKATERS.get(player.getUUID());
        // As vanilla's own speed check, trust whoever owns a singleplayer world.
        if (s == null || !SkateConfig.get(SkateConfig.MOVEMENT_CHECKS)
                || player.server.isSingleplayerOwner(player.getGameProfile())) {
            return null;
        }
        Vec3 now = player.position();
        if (s.last == null) {
            s.last = s.good = now;
            return null;
        }
        Level level = player.level();
        double maxPerTick = SkateConfig.get(SkateConfig.MAX_SPEED) / 20.0;
        int maxAir = (int) Math.round(SkateConfig.get(SkateConfig.MAX_AIR_SECONDS) * 20.0);
        boolean through = crossesSolid(level, s.last, now);
        boolean inside = !through && bodyInSolid(level, now);
        MoveCheck.Verdict verdict = s.check.step(now.x - s.last.x, now.y - s.last.y, now.z - s.last.z, maxPerTick,
                through, inside, grounded(level, now), maxAir);
        s.last = now;
        switch (verdict) {
            case OK -> s.good = now;
            case SUSPECT -> MineSkate3.LOGGER.debug("Skater {} {} (score {})", player.getName().getString(),
                    s.check.reason, s.check.score());
            case STOP -> {
                MineSkate3.LOGGER.warn("Stopped {} skating: {} at {}", player.getName().getString(),
                        s.check.reason, now);
                return s.check.reason;
            }
        }
        return null;
    }

    /** Where to put a stopped skater back. */
    static Vec3 lastGood(ServerPlayer player) {
        Skater s = SKATERS.get(player.getUUID());
        return s != null ? s.good : null;
    }

    private static boolean crossesSolid(Level level, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to);
        int steps = (int) Math.ceil(length / PATH_STEP);
        for (int i = 1; i < steps; i++) {
            Vec3 at = from.lerp(to, (double) i / steps);
            if (bodyInSolid(level, at)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bodyInSolid(Level level, Vec3 feet) {
        for (double up : BODY) {
            if (solidAt(level, feet.x, feet.y + up, feet.z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean solidAt(Level level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
        double lx = x - pos.getX(), ly = y - pos.getY(), lz = z - pos.getZ();
        for (AABB box : shape.toAabbs()) {
            if (box.contains(lx, ly, lz)) {
                return true;
            }
        }
        return false;
    }

    /** Anything at all under or right beside the skater: ground, a rail, a wall to ride. */
    private static boolean grounded(Level level, Vec3 feet) {
        AABB around = new AABB(feet.x - 0.8, feet.y - 1.5, feet.z - 0.8, feet.x + 0.8, feet.y + 0.5, feet.z + 0.8);
        return level.getBlockStatesIfLoaded(around).anyMatch(state -> !state.isAir());
    }
}
