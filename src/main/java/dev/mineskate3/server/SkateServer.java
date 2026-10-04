package dev.mineskate3.server;

import dev.mineskate3.network.SkateNetwork;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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
        boolean changed = skating ? SKATERS.add(player.getUUID()) : SKATERS.remove(player.getUUID());
        if (changed) {
            serverPlayer.fallDistance = 0;
            PacketDistributor.sendToPlayersTrackingEntity(serverPlayer,
                    new SkateNetwork.RemoteState(serverPlayer.getId(), skating));
        }
    }

    public static void onPose(Player player, float[] data) {
        if (player instanceof ServerPlayer serverPlayer && isSkating(player)) {
            PacketDistributor.sendToPlayersTrackingEntity(serverPlayer,
                    new SkateNetwork.RemotePose(serverPlayer.getId(), data));
        }
    }

    private static void stop(Player player) {
        onState(player, false);
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
        Player player = event.getEntity();
        if (!player.level().isClientSide() && isSkating(player)) {
            player.noPhysics = true;
            player.fallDistance = 0;
        }
    }

    private static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof Player target && isSkating(target)
                && event.getEntity() instanceof ServerPlayer tracker) {
            PacketDistributor.sendToPlayer(tracker, new SkateNetwork.RemoteState(target.getId(), true));
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SKATERS.remove(event.getEntity().getUUID());
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
