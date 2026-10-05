package dev.mineskate3.server;

import dev.mineskate3.network.SkateNetwork;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** The server's side of skate mode: who is skating, and what that waives. */
public final class SkateServer {
    private static final Set<UUID> SKATERS = ConcurrentHashMap.newKeySet();

    private SkateServer() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SkateServer::onFall);
        NeoForge.EVENT_BUS.addListener(SkateServer::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(SkateServer::onStartTracking);
        NeoForge.EVENT_BUS.addListener(SkateServer::onLogout);
        NeoForge.EVENT_BUS.addListener(SkateServer::onDeath);
        NeoForge.EVENT_BUS.addListener(SkateServer::onDimensionChange);
    }

    public static boolean isSkating(Player player) {
        return player != null && SKATERS.contains(player.getUUID());
    }

    public static void onState(Player player, boolean skating) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (skating) {
            String refusal = refusal(serverPlayer);
            if (refusal != null) {
                PacketDistributor.sendToPlayer(serverPlayer, new SkateNetwork.Stop(refusal));
                return;
            }
        }
        boolean changed = skating ? SKATERS.add(player.getUUID()) : SKATERS.remove(player.getUUID());
        if (skating && changed) {
            SkateGuard.started(serverPlayer);
        } else if (!skating) {
            SkateGuard.stopped(serverPlayer);
        }
        if (changed) {
            serverPlayer.fallDistance = 0;
            PacketDistributor.sendToPlayersTrackingEntity(serverPlayer,
                    new SkateNetwork.RemoteState(serverPlayer.getId(), skating));
        }
    }

    public static void onPose(Player player, float[] data, SkateNetwork.Bones bones) {
        if (player instanceof ServerPlayer serverPlayer && isSkating(player)) {
            PacketDistributor.sendToPlayersTrackingEntity(serverPlayer,
                    new SkateNetwork.RemotePose(serverPlayer.getId(), data, bones));
        }
    }

    private static String refusal(ServerPlayer player) {
        if (!SkateConfig.get(SkateConfig.ALLOW_SKATING)) {
            return "Skate 3 mode is turned off on this server";
        }
        if (!player.hasPermissions(SkateConfig.get(SkateConfig.PERMISSION_LEVEL))) {
            return "You don't have permission to skate on this server";
        }
        return null;
    }

    private static void stop(Player player) {
        onState(player, false);
    }

    /** Takes a skater off the board from the server side, back to where they last moved cleanly. */
    private static void forceStop(ServerPlayer player, String reason) {
        Vec3 back = SkateGuard.lastGood(player);
        onState(player, false);
        PacketDistributor.sendToPlayer(player, new SkateNetwork.Stop("Skate 3 mode stopped: " + reason));
        player.noPhysics = false;
        if (back != null) {
            player.connection.teleport(back.x, back.y, back.z, player.getYRot(), player.getXRot());
        }
    }

    private static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player player && isSkating(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * The skater's client moves them along Skate's own collision, which can
     * disagree with Minecraft's boxes (grinding an edge, a rail, a curved
     * landing). Treat skaters like noclip for the movement check, as
     * spectators are. Player.tick resets noPhysics at its start, so this only
     * lasts until the next tick, covering the movement packets in between.
     */
    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isSkating(player)) {
            return;
        }
        String failed = SkateGuard.tick(player);
        if (failed != null) {
            forceStop(player, failed);
            return;
        }
        player.noPhysics = true;
        player.fallDistance = 0;
    }

    private static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof Player target && isSkating(target)
                && event.getEntity() instanceof ServerPlayer tracker) {
            PacketDistributor.sendToPlayer(tracker, new SkateNetwork.RemoteState(target.getId(), true));
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SKATERS.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            SkateGuard.stopped(player);
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Player player && isSkating(player)) {
            stop(player);
        }
    }

    private static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        stop(event.getEntity());
    }
}
