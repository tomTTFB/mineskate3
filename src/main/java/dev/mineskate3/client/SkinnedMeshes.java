package dev.mineskate3.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SkateData;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The Skate 3 board and skater from this player's converted data, skinned in
 * native code with any skeleton's bones: the local session's, or another
 * player's relayed over the network. Loaded once, off the render thread.
 */
public final class SkinnedMeshes {
    private static final class Mesh {
        int[] layout;
        int[] indices;
        ResourceLocation[] textures;
        float[] vertices;
    }

    private static volatile int loadState = -1; // -1 not started, 0 loading or failed, 1 board, 2 board and skater
    private static volatile boolean loading;
    private static long lastAttempt;
    private static boolean uploaded;
    private static final Mesh[] MESHES = {new Mesh(), new Mesh()};
    private static int boneCount;
    private static int layoutHash;

    private SkinnedMeshes() {}

    /** Starts loading when converted data exists; true once meshes are ready to draw. */
    public static boolean ready() {
        if (uploaded) {
            return true;
        }
        if (loadState > 0 && !loading) {
            upload();
            return uploaded;
        }
        long now = System.currentTimeMillis();
        if (loading || now - lastAttempt < 3000 || !SkateData.ready()) {
            return false;
        }
        lastAttempt = now;
        loading = true;
        Thread thread = new Thread(() -> {
            try {
                NativeSkate.load(SkateData.root().resolve("natives"));
                String root = SkateData.assets().toAbsolutePath().toString();
                int state = NativeSkate.meshLoad(root);
                NativeSkate.meshUse(root);
                loadState = state;
            } catch (Exception e) {
                MineSkate3.LOGGER.warn("Skate meshes unavailable: {}", e.getMessage());
                loadState = 0;
            } finally {
                loading = false;
            }
        }, "mineskate3-meshes");
        thread.setDaemon(true);
        thread.start();
        return false;
    }

    public static boolean hasSkater() {
        return ready() && loadState == 2;
    }

    public static int boneCount() {
        return ready() ? boneCount : 0;
    }

    public static int layoutHash() {
        return ready() ? layoutHash : 0;
    }

    private static void upload() {
        Minecraft mc = Minecraft.getInstance();
        int meshes = loadState == 2 ? 2 : 1;
        for (int which = 0; which < meshes; which++) {
            Mesh mesh = MESHES[which];
            mesh.layout = NativeSkate.meshLayout(which);
            mesh.indices = NativeSkate.meshIndices(which);
            if (mesh.layout == null || mesh.layout.length < 2 || mesh.indices == null) {
                loadState = 0;
                return;
            }
            mesh.vertices = new float[mesh.layout[0] * 8];
            int surfaces = mesh.layout[1];
            mesh.textures = new ResourceLocation[surfaces];
            Map<Integer, ResourceLocation> byImage = new HashMap<>();
            for (int s = 0; s < surfaces; s++) {
                int image = mesh.layout[2 + s * 3];
                final int w = which;
                mesh.textures[s] = byImage.computeIfAbsent(image, i -> {
                    ResourceLocation location =
                            ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "mesh/" + w + "/" + i);
                    try {
                        byte[] bytes = NativeSkate.meshTexture(w, i);
                        NativeImage decoded = NativeImage.read(new ByteArrayInputStream(bytes));
                        mc.getTextureManager().register(location, new DynamicTexture(decoded));
                        return location;
                    } catch (Exception e) {
                        MineSkate3.LOGGER.warn("Skate texture {}/{} could not be decoded", w, i, e);
                        return TrickHud.white();
                    }
                });
            }
        }
        boneCount = NativeSkate.meshBoneCount();
        layoutHash = NativeSkate.meshLayoutHash();
        uploaded = true;
        MineSkate3.LOGGER.info("Skate meshes ready: {} bones, skater model {}", boneCount,
                loadState == 2 ? "available" : "missing");
    }

    /**
     * Draws mesh `which` skinned by `bones` (12 floats each, in a space that
     * `ox, oy, oz` takes to the pose stack's origin). False if it cannot.
     */
    public static boolean render(int which, float[] bones, PoseStack poseStack, MultiBufferSource buffers,
            int light, float ox, float oy, float oz) {
        if (!ready() || (which == NativeSkate.MESH_SKATER && loadState != 2) || bones == null
                || bones.length < boneCount * 12) {
            return false;
        }
        Mesh mesh = MESHES[which];
        if (NativeSkate.meshSkin(which, bones, mesh.vertices) == 0) {
            return false;
        }
        PoseStack.Pose pose = poseStack.last();
        int surfaces = mesh.layout[1];
        float[] d = mesh.vertices;
        for (int s = 0; s < surfaces; s++) {
            int first = mesh.layout[3 + s * 3];
            int count = mesh.layout[4 + s * 3];
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(mesh.textures[s]));
            for (int i = first; i + 2 < first + count; i += 3) {
                // Entity render types draw quads: repeat the last corner.
                for (int corner : new int[] {i, i + 1, i + 2, i + 2}) {
                    int v = mesh.indices[corner] * 8;
                    consumer.addVertex(pose, d[v] + ox, d[v + 1] + oy, d[v + 2] + oz)
                            .setColor(0xFFFFFFFF)
                            .setUv(d[v + 6], d[v + 7])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(pose, d[v + 3], d[v + 4], d[v + 5]);
                }
            }
        }
        return true;
    }
}
