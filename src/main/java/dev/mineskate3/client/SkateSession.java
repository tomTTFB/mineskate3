package dev.mineskate3.client;

import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SetupScreen;
import dev.mineskate3.client.setup.SkateData;
import dev.mineskate3.network.SkateNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The local player's skate mode. The Skate engine runs on its own thread in
 * native code; this class feeds it input and block collision, and moves the
 * player, camera and model to what it publishes.
 */
public final class SkateSession {
    /** Rebuild collision once the skater is this far from where it was gathered. */
    private static final double RECENTRE_DISTANCE = 10.0;
    /** After a block changes, wait this long for more before rebuilding (explosions, pistons). */
    private static final long CHANGE_SETTLE_MILLIS = 100;
    /** Rebuild this often regardless, in case a change slipped past the listeners. */
    private static final long REFRESH_MILLIS = 15000;

    public record CameraPose(Vec3 position, float yaw, float pitch, float roll, float fov) {}

    private static final SkateSession INSTANCE = new SkateSession();

    public static SkateSession get() {
        return INSTANCE;
    }

    private long handle;
    private boolean entering;
    private boolean active;
    private Level level;
    private double originX;
    private double originY;
    private double originZ;
    private BlockPos collisionCentre;
    private long collisionTime;
    /** When a block inside the gathered area changed since the last build, else 0. */
    private long changedAt;
    private Surfaces.Surface surface = Surfaces.Surface.NORMAL;
    private final Rumble rumble = new Rumble();
    private final float[] pose = new float[NativeSkate.POSE_LENGTH];
    private long poseGeneration = -1;
    private boolean posePublished;
    private volatile CameraPose camera;
    private final PadInput pad = new PadInput();
    private boolean chordWasHeld;
    private String state = "";
    private String lastError;

    private SkateSession() {}

    public boolean active() {
        return active;
    }

    public boolean entering() {
        return entering;
    }

    public long handle() {
        return handle;
    }

    public PadInput pad() {
        return pad;
    }

    public String state() {
        return state;
    }

    public float[] pose() {
        return pose;
    }

    public boolean posePublished() {
        return active && posePublished;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    /** The skate camera while skating, read by CameraMixin; null otherwise. */
    public CameraPose camera() {
        return active ? camera : null;
    }

    public String lastError() {
        return lastError;
    }

    public void toggle() {
        if (active || entering) {
            stop("");
        } else {
            start();
        }
    }

    private void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(text), true);
        }
    }

    private void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!SkateData.ready()) {
            mc.setScreen(new SetupScreen(mc.screen));
            return;
        }
        try {
            NativeSkate.load(SkateData.root().resolve("natives"));
        } catch (Exception e) {
            lastError = e.getMessage();
            MineSkate3.LOGGER.error("Skate engine library unavailable", e);
            message("Skate engine unavailable: " + e.getMessage());
            return;
        }
        if (handle != 0 && NativeSkate.status(handle) == NativeSkate.STATUS_FAILED) {
            NativeSkate.destroy(handle);
            handle = 0;
        }
        if (handle == 0) {
            try {
                handle = NativeSkate.create(SkateData.assets().toAbsolutePath().toString());
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                message("Skate failed to start: " + e.getMessage());
                return;
            }
        }
        entering = true;
        lastError = null;
        message("Loading Skate 3...");
    }

    /** Called once the native session reports ready while entering. */
    private void activate() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            entering = false;
            return;
        }
        level = mc.level;
        BlockPos at = player.blockPosition();
        originX = at.getX();
        originY = at.getY();
        originZ = at.getZ();
        sendCollision(at);
        // Skate's skater faces local +z; Minecraft's yaw turns clockwise from +z.
        float heading = (float) Math.toRadians(-player.getYRot());
        NativeSkate.activate(handle,
                (float) (player.getX() - originX),
                (float) (player.getY() - originY) + 0.05f,
                (float) (player.getZ() - originZ),
                heading, aspect());
        entering = false;
        active = true;
        posePublished = false;
        poseGeneration = -1;
        send(new SkateNetwork.State(true));
        message("Skate 3 mode on");
    }

    public void stop(String reason) {
        boolean was = active || entering;
        if (handle != 0 && active) {
            NativeSkate.suspend(handle);
        }
        active = false;
        entering = false;
        camera = null;
        posePublished = false;
        SkateAudio.stopLocal();
        rumble.stop();
        level = null;
        surface = Surfaces.Surface.NORMAL;
        if (was) {
            send(new SkateNetwork.State(false));
            message(reason.isEmpty() ? "Skate 3 mode off" : reason);
        }
    }

    /** Only servers with this mod understand skate packets. */
    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.hasChannel(payload)) {
            PacketDistributor.sendToServer(payload);
        }
    }

    private float aspect() {
        var window = Minecraft.getInstance().getWindow();
        return window.getHeight() > 0 ? (float) window.getWidth() / window.getHeight() : 16f / 9f;
    }

    private void sendCollision(BlockPos centre) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long started = System.nanoTime();
        BlockCollision blocks = BlockCollision.gather(mc.level, centre, originX, originY, originZ);
        NativeSkate.collision(handle, blocks.triangles, blocks.count, blocks.noLip, blocks.rails, blocks.railPoints,
                blocks.minX, blocks.minZ, blocks.maxX, blocks.maxZ);
        collisionCentre = blocks.centre;
        collisionTime = System.currentTimeMillis();
        changedAt = 0;
        MineSkate3.LOGGER.debug("Skate collision: {} triangles, {} block rails around {} in {} ms", blocks.count,
                blocks.railPoints.length, centre, (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * A block changed on the client (ClientLevelMixin). Changes inside the
     * gathered area that touch collision schedule a rebuild.
     */
    public void blockChanged(Level changed, BlockPos pos, net.minecraft.world.level.block.state.BlockState before,
            net.minecraft.world.level.block.state.BlockState after) {
        if (!active || changed != level || collisionCentre == null || before == after || !inGathered(pos)) {
            return;
        }
        var empty = net.minecraft.world.phys.shapes.CollisionContext.empty();
        if (before.getCollisionShape(changed, pos, empty).isEmpty()
                && after.getCollisionShape(changed, pos, empty).isEmpty()) {
            return;
        }
        if (changedAt == 0) {
            changedAt = System.currentTimeMillis();
        }
    }

    /** A chunk arrived: it may fill a hole in the gathered area. */
    public void chunkLoaded(Level loaded, net.minecraft.world.level.ChunkPos chunk) {
        if (!active || loaded != level || collisionCentre == null) {
            return;
        }
        int r = BlockCollision.RADIUS + 1;
        if (chunk.getMaxBlockX() >= collisionCentre.getX() - r && chunk.getMinBlockX() <= collisionCentre.getX() + r
                && chunk.getMaxBlockZ() >= collisionCentre.getZ() - r
                && chunk.getMinBlockZ() <= collisionCentre.getZ() + r && changedAt == 0) {
            changedAt = System.currentTimeMillis();
        }
    }

    private boolean inGathered(BlockPos pos) {
        int r = BlockCollision.RADIUS + 1;
        int dy = pos.getY() - collisionCentre.getY();
        return Math.abs(pos.getX() - collisionCentre.getX()) <= r && Math.abs(pos.getZ() - collisionCentre.getZ()) <= r
                && dy >= -BlockCollision.BELOW - 1 && dy <= BlockCollision.ABOVE + 1;
    }

    /** The server stopped this player's skating (not allowed, or a movement check failed). */
    public void forcedStop(String reason) {
        if (active || entering) {
            stop(reason.isEmpty() ? "Skate 3 mode stopped by the server" : reason);
        }
    }

    /** Client tick: toggling, loading, collision streaming and failure handling. */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean chord = pad.toggleChordHeld();
        if (chord && !chordWasHeld && mc.screen == null) {
            toggle();
        }
        chordWasHeld = chord;

        if (!active && !entering) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isDeadOrDying() || player.isSpectator()
                || (active && mc.level != level)) {
            stop("");
            return;
        }
        int status = NativeSkate.status(handle);
        if (status == NativeSkate.STATUS_FAILED) {
            lastError = NativeSkate.error(handle);
            MineSkate3.LOGGER.error("Skate session failed: {}", lastError);
            stop("Skate stopped: " + lastError);
            return;
        }
        if (entering) {
            if (status == NativeSkate.STATUS_READY || status == NativeSkate.STATUS_ACTIVE) {
                activate();
            }
            return;
        }
        state = NativeSkate.state(handle);
        BlockPos here = player.blockPosition();
        long now = System.currentTimeMillis();
        boolean far = collisionCentre == null || Math.sqrt(collisionCentre.distSqr(here)) > RECENTRE_DISTANCE;
        boolean changed = changedAt != 0 && now - changedAt >= CHANGE_SETTLE_MILLIS;
        boolean stale = now - collisionTime > REFRESH_MILLIS;
        if (far || changed || stale) {
            sendCollision(here);
        }
        if (posePublished) {
            surface = Surfaces.under(mc.level, originX + pose[NativeSkate.ROOT + 12],
                    originY + pose[NativeSkate.ROOT + 13], originZ + pose[NativeSkate.ROOT + 14]);
            send(new SkateNetwork.Pose(SkaterRenderer.networkPose(pose), SkaterRenderer.networkBones(handle, pose)));
        }
    }

    /** Every rendered frame: input in, newest pose and camera out. */
    public void frame(float dt) {
        if (!active || handle == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            rumble.stop();
            return;
        }
        pad.poll(dt, mc.screen != null);
        Surfaces.Surface under = surface;
        NativeSkate.step(handle, dt, pad.connected, pad.buttons, pad.leftTrigger, pad.rightTrigger,
                pad.lx, pad.ly, pad.rx, pad.ry, under.drag(), under.glide(), under.bounce(), aspect());
        long generation = NativeSkate.pose(handle, pose);
        if (generation < 0 || generation == poseGeneration) {
            rumble.update(null, dt, pad);
            return;
        }
        poseGeneration = generation;
        posePublished = true;
        if (pose[NativeSkate.CAMERA_VALID] != 0f) {
            camera = cameraFrom(pose);
        }
        SkateAudio.local(pose, originX + pose[NativeSkate.ROOT + 12], originY + pose[NativeSkate.ROOT + 13],
                originZ + pose[NativeSkate.ROOT + 14]);
        rumble.update(pose, dt, pad);
    }

    private CameraPose cameraFrom(float[] p) {
        Vec3 position = new Vec3(p[NativeSkate.CAMERA_POSITION] + originX,
                p[NativeSkate.CAMERA_POSITION + 1] + originY,
                p[NativeSkate.CAMERA_POSITION + 2] + originZ);
        int b = NativeSkate.CAMERA_BASIS;
        Vector3f up = new Vector3f(p[b + 3], p[b + 4], p[b + 5]).normalize();
        Vector3f forward = new Vector3f(p[b + 6], p[b + 7], p[b + 8]).normalize();
        // Minecraft looks along (-sin yaw cos pitch, -sin pitch, cos yaw cos pitch).
        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, -forward.y))));
        double yawRad = Math.toRadians(yaw);
        Vector3f right0 = new Vector3f((float) -Math.cos(yawRad), 0f, (float) -Math.sin(yawRad));
        Vector3f up0 = new Vector3f(right0).cross(forward).normalize();
        float roll = (float) Math.toDegrees(Math.atan2(up.dot(right0), up.dot(up0)));
        float fov = p[NativeSkate.CAMERA_FOV];
        return new CameraPose(position, yaw, pitch, roll, fov > 1f && fov < 170f ? fov : 70f);
    }

    /** After the local player's tick: put them where the skater is. */
    public void afterPlayerTick(LocalPlayer player) {
        if (!active || !posePublished) {
            return;
        }
        Matrix4f root = new Matrix4f().set(pose, NativeSkate.ROOT);
        Vector3f at = root.getTranslation(new Vector3f());
        // Face the way the skater faces (local +z).
        Vector3f forward = root.transformDirection(new Vector3f(0, 0, 1));
        float yaw = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
        player.setPos(at.x + originX, at.y + originY, at.z + originZ);
        player.setDeltaMovement(Vec3.ZERO);
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;
        player.fallDistance = 0;
        player.setOnGround(true);
    }

    /** The world is going away: pause skating; the native session stays loaded. */
    public void onLogout() {
        SkateAudio.stopLocal();
        rumble.stop();
        active = false;
        entering = false;
        camera = null;
        level = null;
        if (handle != 0) {
            NativeSkate.suspend(handle);
        }
    }
}
