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
    /** Rebuild collision this often regardless, to pick up block changes. */
    private static final long REFRESH_MILLIS = 2000;

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
    private final float[] pose = new float[160];
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
        PacketDistributor.sendToServer(new SkateNetwork.State(true));
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
        level = null;
        if (was) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                PacketDistributor.sendToServer(new SkateNetwork.State(false));
            }
            message(reason.isEmpty() ? "Skate 3 mode off" : reason);
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
        NativeSkate.collision(handle, blocks.triangles, blocks.count, blocks.minX, blocks.minZ, blocks.maxX,
                blocks.maxZ);
        collisionCentre = blocks.centre;
        collisionTime = System.currentTimeMillis();
        MineSkate3.LOGGER.debug("Skate collision: {} triangles around {} in {} ms", blocks.count, centre,
                (System.nanoTime() - started) / 1_000_000);
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
        boolean far = collisionCentre == null || Math.sqrt(collisionCentre.distSqr(here)) > RECENTRE_DISTANCE;
        boolean stale = System.currentTimeMillis() - collisionTime > REFRESH_MILLIS;
        if (far || stale) {
            sendCollision(here);
        }
        if (posePublished) {
            PacketDistributor.sendToServer(new SkateNetwork.Pose(SkaterRenderer.networkPose(pose)));
        }
    }

    /** Every rendered frame: input in, newest pose and camera out. */
    public void frame(float dt) {
        if (!active || handle == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return;
        }
        pad.poll(dt, mc.screen != null);
        NativeSkate.step(handle, dt, pad.connected, pad.buttons, pad.leftTrigger, pad.rightTrigger,
                pad.lx, pad.ly, pad.rx, pad.ry, aspect());
        long generation = NativeSkate.pose(handle, pose);
        if (generation < 0 || generation == poseGeneration) {
            return;
        }
        poseGeneration = generation;
        posePublished = true;
        if (pose[NativeSkate.CAMERA_VALID] != 0f) {
            camera = cameraFrom(pose);
        }
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
        active = false;
        entering = false;
        camera = null;
        level = null;
        if (handle != 0) {
            NativeSkate.suspend(handle);
        }
    }
}
