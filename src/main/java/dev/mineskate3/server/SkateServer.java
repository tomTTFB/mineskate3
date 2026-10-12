package dev.mineskate3.server;

import dev.mineskate3.network.SkateNetwork;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The server's side of skate mode: who is skating, and what that waives.
 *
 * The mod is optional on clients. Skate packets only go to players whose
 * client negotiated the mod's channels (sending one to anyone else is an
 * error); everyone else sees the skater as a normal player standing on a
 * plank (see {@link SkatePlank}).
 */
public final class SkateServer {
    private static final Set<UUID> SKATERS = ConcurrentHashMap.newKeySet();
    /** Skater to the players tracking them who have the mod. */
    private static final Map<UUID, Set<UUID>> WATCHERS = new ConcurrentHashMap<>();
    private static final Map<UUID, SkatePlank> PLANKS = new ConcurrentHashMap<>();

    private SkateServer() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SkateServer::onFall);
        NeoForge.EVENT_BUS.addListener(SkateServer::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(SkateServer::onStartTracking);
        NeoForge.EVENT_BUS.addListener(SkateServer::onStopTracking);
        NeoForge.EVENT_BUS.addListener(SkateServer::onLogout);
        NeoForge.EVENT_BUS.addListener(SkateServer::onDeath);
        NeoForge.EVENT_BUS.addListener(SkateServer::onDimensionChange);
    }

    public static boolean isSkating(Player player) {
        return player != null && SKATERS.contains(player.getUUID());
    }

    /** Whether this player's client has the mod (negotiated its optional channels). */
    public static boolean hasMod(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(SkateNetwork.RemoteState.TYPE);
    }

    public static void onState(Player player, boolean skating) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        boolean changed = skating ? SKATERS.add(player.getUUID()) : SKATERS.remove(player.getUUID());
        if (changed) {
            serverPlayer.fallDistance = 0;
            sendToWatchers(serverPlayer, new SkateNetwork.RemoteState(serverPlayer.getId(), skating));
            if (skating) {
                followWithPlank(serverPlayer);
            } else {
                removePlank(serverPlayer.getUUID());
            }
        }
    }

    public static void onPose(Player player, float[] data, SkateNetwork.Bones bones) {
        if (player instanceof ServerPlayer serverPlayer && isSkating(player)) {
            sendToWatchers(serverPlayer, new SkateNetwork.RemotePose(serverPlayer.getId(), data, bones));
        }
    }

    private static void sendToWatchers(ServerPlayer skater, CustomPacketPayload payload) {
        Set<UUID> watchers = WATCHERS.get(skater.getUUID());
        if (watchers == null || skater.getServer() == null) {
            return;
        }
        for (UUID id : watchers) {
            ServerPlayer watcher = skater.getServer().getPlayerList().getPlayer(id);
            if (watcher != null && hasMod(watcher)) {
                PacketDistributor.sendToPlayer(watcher, payload);
            }
        }
    }

    /** Keep the vanilla-visible plank under a skater, making it if it is missing. */
    private static void followWithPlank(ServerPlayer skater) {
        SkatePlank plank = PLANKS.get(skater.getUUID());
        if (plank == null || plank.isRemoved() || plank.level() != skater.level()) {
            if (plank != null) {
                plank.discard();
            }
            plank = new SkatePlank(skater.serverLevel());
            PLANKS.put(skater.getUUID(), plank);
            plank.follow(skater);
            skater.serverLevel().addFreshEntity(plank);
        } else {
            plank.follow(skater);
        }
    }

    private static void removePlank(UUID skater) {
        SkatePlank plank = PLANKS.remove(skater);
        if (plank != null) {
            plank.discard();
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
            if (player instanceof ServerPlayer serverPlayer) {
                followWithPlank(serverPlayer);
            }
        }
    }

    private static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof Player target && event.getEntity() instanceof ServerPlayer tracker
                && hasMod(tracker)) {
            WATCHERS.computeIfAbsent(target.getUUID(), id -> ConcurrentHashMap.newKeySet()).add(tracker.getUUID());
            if (isSkating(target)) {
                PacketDistributor.sendToPlayer(tracker, new SkateNetwork.RemoteState(target.getId(), true));
            }
        }
    }

    private static void onStopTracking(PlayerEvent.StopTracking event) {
        if (event.getTarget() instanceof Player target) {
            Set<UUID> watchers = WATCHERS.get(target.getUUID());
            if (watchers != null) {
                watchers.remove(event.getEntity().getUUID());
            }
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        SKATERS.remove(id);
        WATCHERS.remove(id);
        WATCHERS.values().forEach(watchers -> watchers.remove(id));
        removePlank(id);
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
