package dev.mineskate3.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.network.SkateNetwork;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws skaters: the vanilla player model with each of its six parts aimed by
 * the Skate skeleton, and the board. The local player gets the real skinned
 * Skate 3 board; other players get a box fitted to it, since their board mesh
 * lives in their own converted data.
 */
public final class SkaterRenderer {
    private static final ResourceLocation REMOTE_BOARD =
            ResourceLocation.withDefaultNamespace("textures/block/dark_oak_planks.png");
    private static final long REMOTE_TIMEOUT_MILLIS = 2000;

    private record Remote(float[] data, long time) {}

    private static final Map<Integer, Remote> REMOTES = new HashMap<>();

    // The local board, read from the native session once it has loaded.
    private static long boardHandle;
    private static int[] boardIndices;
    private static int[] boardLayout;
    private static ResourceLocation[] boardTextures;
    private static float[] boardVertices = new float[0];

    private SkaterRenderer() {}

    // ---- remote skaters ----

    public static void remoteState(SkateNetwork.RemoteState state) {
        if (!state.skating()) {
            REMOTES.remove(state.entityId());
        }
    }

    public static void remotePose(SkateNetwork.RemotePose pose) {
        REMOTES.put(pose.entityId(), new Remote(pose.data(), System.currentTimeMillis()));
    }

    public static void clearRemotes() {
        REMOTES.clear();
    }

    /** Remote skaters draw from their relayed pose, at their interpolated position. */
    public static boolean renderRemote(AbstractClientPlayer player, PoseStack poseStack, MultiBufferSource buffers,
            int light) {
        Remote remote = REMOTES.get(player.getId());
        if (remote == null) {
            return false;
        }
        if (System.currentTimeMillis() - remote.time() > REMOTE_TIMEOUT_MILLIS) {
            REMOTES.remove(player.getId());
            return false;
        }
        Matrix4f[] parts = new Matrix4f[6];
        for (int i = 0; i < 6; i++) {
            parts[i] = unpack(remote.data(), i * 12);
        }
        renderParts(player, parts, poseStack, buffers, light, 0, 0, 0);
        renderBox(unpack(remote.data(), 6 * 12), poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(REMOTE_BOARD)),
                light, 0, 0, 0);
        return true;
    }

    private static Matrix4f unpack(float[] d, int at) {
        return new Matrix4f(
                d[at], d[at + 1], d[at + 2], 0,
                d[at + 3], d[at + 4], d[at + 5], 0,
                d[at + 6], d[at + 7], d[at + 8], 0,
                d[at + 9], d[at + 10], d[at + 11], 1);
    }

    /** The local pose for the network: parts and board relative to the skater's root. */
    public static float[] networkPose(float[] pose) {
        float[] out = new float[SkateNetwork.POSE_FLOATS];
        float rx = pose[NativeSkate.ROOT + 12];
        float ry = pose[NativeSkate.ROOT + 13];
        float rz = pose[NativeSkate.ROOT + 14];
        for (int i = 0; i < 7; i++) {
            int src = i < 6 ? NativeSkate.PARTS + i * 16 : NativeSkate.BOARD;
            int dst = i * 12;
            for (int c = 0; c < 3; c++) {
                out[dst + c * 3] = pose[src + c * 4];
                out[dst + c * 3 + 1] = pose[src + c * 4 + 1];
                out[dst + c * 3 + 2] = pose[src + c * 4 + 2];
            }
            out[dst + 9] = pose[src + 12] - rx;
            out[dst + 10] = pose[src + 13] - ry;
            out[dst + 11] = pose[src + 14] - rz;
        }
        return out;
    }

    // ---- the local skater ----

    public static void renderLocal(PoseStack poseStack, Vec3 cameraPosition) {
        SkateSession session = SkateSession.get();
        Minecraft mc = Minecraft.getInstance();
        if (!session.posePublished() || mc.player == null || mc.level == null) {
            return;
        }
        float[] pose = session.pose();
        double ox = session.originX() - cameraPosition.x;
        double oy = session.originY() - cameraPosition.y;
        double oz = session.originZ() - cameraPosition.z;
        BlockPos lightAt = BlockPos.containing(
                session.originX() + pose[NativeSkate.ROOT + 12],
                session.originY() + pose[NativeSkate.ROOT + 13] + 0.5,
                session.originZ() + pose[NativeSkate.ROOT + 14]);
        int light = LevelRenderer.getLightColor(mc.level, lightAt);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        Matrix4f[] parts = new Matrix4f[6];
        for (int i = 0; i < 6; i++) {
            parts[i] = new Matrix4f().set(pose, NativeSkate.PARTS + i * 16);
        }
        renderParts(mc.player, parts, poseStack, buffers, light, ox, oy, oz);
        if (!renderBoard(session, poseStack, buffers, light, ox, oy, oz)) {
            renderBox(new Matrix4f().set(pose, NativeSkate.BOARD), poseStack,
                    buffers.getBuffer(RenderType.entityCutoutNoCull(REMOTE_BOARD)), light, ox, oy, oz);
        }
        buffers.endBatch();
    }

    @SuppressWarnings("unchecked")
    private static PlayerModel<AbstractClientPlayer> modelFor(AbstractClientPlayer player) {
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        return renderer instanceof PlayerRenderer playerRenderer ? playerRenderer.getModel() : null;
    }

    private static void renderParts(AbstractClientPlayer player, Matrix4f[] parts, PoseStack poseStack,
            MultiBufferSource buffers, int light, double ox, double oy, double oz) {
        PlayerModel<AbstractClientPlayer> model = modelFor(player);
        if (model == null) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(player.getSkin().texture()));
        int overlay = OverlayTexture.NO_OVERLAY;
        part(model.head, parts[0], poseStack, consumer, light, overlay, ox, oy, oz, true);
        part(model.hat, parts[0], poseStack, consumer, light, overlay, ox, oy, oz,
                player.isModelPartShown(PlayerModelPart.HAT));
        part(model.body, parts[1], poseStack, consumer, light, overlay, ox, oy, oz, true);
        part(model.jacket, parts[1], poseStack, consumer, light, overlay, ox, oy, oz,
                player.isModelPartShown(PlayerModelPart.JACKET));
        part(model.rightArm, parts[2], poseStack, consumer, light, overlay, ox, oy, oz, true);
        part(model.rightSleeve, parts[2], poseStack, consumer, light, overlay, ox, oy, oz,
                player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        part(model.leftArm, parts[3], poseStack, consumer, light, overlay, ox, oy, oz, true);
        part(model.leftSleeve, parts[3], poseStack, consumer, light, overlay, ox, oy, oz,
                player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        part(model.rightLeg, parts[4], poseStack, consumer, light, overlay, ox, oy, oz, true);
        part(model.rightPants, parts[4], poseStack, consumer, light, overlay, ox, oy, oz,
                player.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG));
        part(model.leftLeg, parts[5], poseStack, consumer, light, overlay, ox, oy, oz, true);
        part(model.leftPants, parts[5], poseStack, consumer, light, overlay, ox, oy, oz,
                player.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG));
    }

    /** Rotation-only normal matrix: the part matrices carry a stretch along the limb. */
    private static Matrix3f normalMatrix(Matrix4f m) {
        Vector3f x = m.getColumn(0, new Vector3f()).normalize();
        Vector3f y = m.getColumn(1, new Vector3f()).normalize();
        Vector3f z = m.getColumn(2, new Vector3f()).normalize();
        return new Matrix3f(x, y, z);
    }

    private static void part(ModelPart part, Matrix4f matrix, PoseStack poseStack, VertexConsumer consumer,
            int light, int overlay, double ox, double oy, double oz, boolean visible) {
        if (!visible || matrix.m33() == 0f) {
            return;
        }
        float x = part.x, y = part.y, z = part.z;
        float xRot = part.xRot, yRot = part.yRot, zRot = part.zRot;
        boolean wasVisible = part.visible;
        part.x = part.y = part.z = 0;
        part.xRot = part.yRot = part.zRot = 0;
        part.visible = true;
        poseStack.pushPose();
        PoseStack.Pose last = poseStack.last();
        last.pose().translate((float) ox, (float) oy, (float) oz).mul(matrix);
        last.normal().mul(normalMatrix(matrix));
        part.render(poseStack, consumer, light, overlay);
        poseStack.popPose();
        part.x = x;
        part.y = y;
        part.z = z;
        part.xRot = xRot;
        part.yRot = yRot;
        part.zRot = zRot;
        part.visible = wasVisible;
    }

    private static boolean loadBoard(long handle) {
        if (boardHandle == handle && boardIndices != null) {
            return true;
        }
        int status = NativeSkate.status(handle);
        if (status != NativeSkate.STATUS_READY && status != NativeSkate.STATUS_ACTIVE) {
            return false;
        }
        int[] layout = NativeSkate.boardLayout(handle);
        if (layout == null || layout.length < 2) {
            return false;
        }
        int surfaces = layout[1];
        ResourceLocation[] textures = new ResourceLocation[surfaces];
        Minecraft mc = Minecraft.getInstance();
        Map<Integer, ResourceLocation> byImage = new HashMap<>();
        for (int s = 0; s < surfaces; s++) {
            int image = layout[2 + s * 3];
            textures[s] = byImage.computeIfAbsent(image, i -> {
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "board/" + i);
                byte[] bytes = NativeSkate.boardTexture(handle, i);
                try {
                    NativeImage decoded = NativeImage.read(new ByteArrayInputStream(bytes));
                    mc.getTextureManager().register(location, new DynamicTexture(decoded));
                    return location;
                } catch (Exception e) {
                    MineSkate3.LOGGER.warn("Board texture {} could not be decoded", i, e);
                    return REMOTE_BOARD;
                }
            });
        }
        boardIndices = NativeSkate.boardIndices(handle);
        boardLayout = layout;
        boardTextures = textures;
        boardVertices = new float[layout[0] * 8];
        boardHandle = handle;
        return true;
    }

    private static boolean renderBoard(SkateSession session, PoseStack poseStack, MultiBufferSource buffers,
            int light, double ox, double oy, double oz) {
        long handle = session.handle();
        if (handle == 0 || !loadBoard(handle)) {
            return false;
        }
        if (NativeSkate.boardVertices(handle, boardVertices) == 0) {
            return false;
        }
        PoseStack.Pose last = poseStack.last();
        int surfaces = boardLayout[1];
        float fx = (float) ox, fy = (float) oy, fz = (float) oz;
        for (int s = 0; s < surfaces; s++) {
            int first = boardLayout[3 + s * 3];
            int count = boardLayout[4 + s * 3];
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(boardTextures[s]));
            for (int i = first; i + 2 < first + count; i += 3) {
                // Entity render types draw quads: repeat the last corner.
                vertex(consumer, last, boardIndices[i], light, fx, fy, fz);
                vertex(consumer, last, boardIndices[i + 1], light, fx, fy, fz);
                vertex(consumer, last, boardIndices[i + 2], light, fx, fy, fz);
                vertex(consumer, last, boardIndices[i + 2], light, fx, fy, fz);
            }
        }
        return true;
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, int index, int light,
            float ox, float oy, float oz) {
        int v = index * 8;
        float[] d = boardVertices;
        consumer.addVertex(pose, d[v] + ox, d[v + 1] + oy, d[v + 2] + oz)
                .setColor(0xFFFFFFFF)
                .setUv(d[v + 6], d[v + 7])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, d[v + 3], d[v + 4], d[v + 5]);
    }

    /** The unit cube centred on the origin, through `matrix`. */
    private static void renderBox(Matrix4f matrix, PoseStack poseStack, VertexConsumer consumer, int light,
            double ox, double oy, double oz) {
        if (matrix.m33() == 0f) {
            return;
        }
        poseStack.pushPose();
        PoseStack.Pose last = poseStack.last();
        last.pose().translate((float) ox, (float) oy, (float) oz).mul(matrix);
        last.normal().mul(normalMatrix(matrix));
        float h = 0.5f;
        float[][] faces = {
            // normal, then four corners counterclockwise from outside
            {0, 1, 0, -h, h, -h, -h, h, h, h, h, h, h, h, -h},
            {0, -1, 0, -h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h},
            {0, 0, -1, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -h},
            {0, 0, 1, -h, -h, h, h, -h, h, h, h, h, -h, h, h},
            {-1, 0, 0, -h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h},
            {1, 0, 0, h, -h, -h, h, h, -h, h, h, h, h, -h, h},
        };
        float[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        for (float[] f : faces) {
            for (int c = 0; c < 4; c++) {
                consumer.addVertex(last, f[3 + c * 3], f[4 + c * 3], f[5 + c * 3])
                        .setColor(0xFFFFFFFF)
                        .setUv(uv[c][0], uv[c][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(last, f[0], f[1], f[2]);
            }
        }
        poseStack.popPose();
    }
}
