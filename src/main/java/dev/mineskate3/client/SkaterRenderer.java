package dev.mineskate3.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mineskate3.network.SkateNetwork;
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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws skaters. With the Skate 3 skater setting on, the converted Skate 3
 * skater; otherwise the vanilla player model with each of its six parts aimed
 * by the Skate skeleton. The board is the real skinned Skate 3 board.
 * Other players are skinned with this player's own copy of the meshes, posed
 * by their relayed bones; without converted data (or with a different bone
 * layout) they fall back to player parts and a box fitted to their board.
 */
public final class SkaterRenderer {
    private static final ResourceLocation REMOTE_BOARD =
            ResourceLocation.withDefaultNamespace("textures/block/dark_oak_planks.png");
    private static final long REMOTE_TIMEOUT_MILLIS = 2000;

    private record Remote(float[] data, SkateNetwork.Bones bones, float[] boneValues, long time) {}

    private static final Map<Integer, Remote> REMOTES = new HashMap<>();

    private static float[] localBones = new float[0];

    private SkaterRenderer() {}

    // ---- remote skaters ----

    public static void remoteState(SkateNetwork.RemoteState state) {
        if (!state.skating()) {
            REMOTES.remove(state.entityId());
            SkateAudio.removeRemote(state.entityId());
        }
    }

    public static void remotePose(SkateNetwork.RemotePose pose) {
        SkateNetwork.Bones bones = pose.bones();
        REMOTES.put(pose.entityId(), new Remote(pose.data(), bones,
                bones.halves().length > 0 ? bones.values() : null, System.currentTimeMillis()));
        SkateAudio.remote(pose.entityId(), pose.data());
    }

    public static void clearRemotes() {
        REMOTES.clear();
        SkateAudio.clear();
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
            SkateAudio.removeRemote(player.getId());
            return false;
        }
        float[] bones = remote.boneValues();
        boolean skinnable = bones != null && SkinnedMeshes.ready()
                && remote.bones().layoutHash() == SkinnedMeshes.layoutHash()
                && bones.length >= SkinnedMeshes.boneCount() * 12;
        boolean drewSkater = skinnable && SkateSettings.skate3Skater()
                && SkinnedMeshes.render(NativeSkate.MESH_SKATER, bones, poseStack, buffers, light, 0, 0, 0);
        if (!drewSkater) {
            Matrix4f[] parts = new Matrix4f[6];
            for (int i = 0; i < 6; i++) {
                parts[i] = unpack(remote.data(), i * 12);
            }
            renderParts(player, parts, poseStack, buffers, light, 0, 0, 0);
        }
        if (!(skinnable && SkinnedMeshes.render(NativeSkate.MESH_BOARD, bones, poseStack, buffers, light, 0, 0, 0))) {
            renderBox(unpack(remote.data(), 6 * 12), poseStack,
                    buffers.getBuffer(RenderType.entityCutoutNoCull(REMOTE_BOARD)), light, 0, 0, 0);
        }
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
        int st = SkateNetwork.STATUS_AT;
        out[st] = pose[NativeSkate.STATUS];
        out[st + 1] = pose[NativeSkate.STATUS + 1];
        out[st + 2] = pose[NativeSkate.STATUS + 2];
        out[st + 3] = pose[NativeSkate.STATUS + 3];
        out[st + 4] = speed(pose);
        return out;
    }

    static float speed(float[] pose) {
        float vx = pose[NativeSkate.VELOCITY], vy = pose[NativeSkate.VELOCITY + 1], vz = pose[NativeSkate.VELOCITY + 2];
        return (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /** The local skin bones for the network, relative to the skater's root. */
    public static SkateNetwork.Bones networkBones(long handle, float[] pose) {
        int needed = SkinnedMeshes.boneCount() * 12;
        if (needed == 0 || handle == 0) {
            return SkateNetwork.Bones.NONE;
        }
        if (localBones.length < needed) {
            localBones = new float[needed];
        }
        int count = NativeSkate.poseBones(handle, localBones);
        if (count < needed) {
            return SkateNetwork.Bones.NONE;
        }
        float[] relative = java.util.Arrays.copyOf(localBones, needed);
        for (int b = 0; b < needed; b += 12) {
            relative[b + 9] -= pose[NativeSkate.ROOT + 12];
            relative[b + 10] -= pose[NativeSkate.ROOT + 13];
            relative[b + 11] -= pose[NativeSkate.ROOT + 14];
        }
        return SkateNetwork.Bones.of(SkinnedMeshes.layoutHash(), relative, needed);
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

        float[] bones = null;
        int needed = SkinnedMeshes.boneCount() * 12;
        if (needed > 0) {
            if (localBones.length < needed) {
                localBones = new float[needed];
            }
            if (NativeSkate.poseBones(session.handle(), localBones) >= needed) {
                bones = localBones;
            }
        }
        float fx = (float) ox, fy = (float) oy, fz = (float) oz;
        boolean drewSkater = bones != null && SkateSettings.skate3Skater()
                && SkinnedMeshes.render(NativeSkate.MESH_SKATER, bones, poseStack, buffers, light, fx, fy, fz);
        if (!drewSkater) {
            Matrix4f[] parts = new Matrix4f[6];
            for (int i = 0; i < 6; i++) {
                parts[i] = new Matrix4f().set(pose, NativeSkate.PARTS + i * 16);
            }
            renderParts(mc.player, parts, poseStack, buffers, light, ox, oy, oz);
        }
        if (bones == null
                || !SkinnedMeshes.render(NativeSkate.MESH_BOARD, bones, poseStack, buffers, light, fx, fy, fz)) {
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
