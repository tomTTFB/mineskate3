package dev.mineskate3.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.mineskate3.MineSkate3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

/**
 * The Trick Guide's backdrop: the original demo set (park/park.json from
 * trick_guide_park.py) with the highlighted trick's demo clip played by the
 * player's own Minecraft model on the Skate 3 board. The native guide plays
 * the clip and places the camera; this class only draws, full screen, with
 * its own projection, before the panel.
 */
final class DemoScene {
    // guideDemo layout (guide.rs, mod layout).
    private static final int EYE = 1;
    private static final int TARGET = 4;
    private static final int PARTS = 7;
    private static final int BOARD = PARTS + 6 * 16;
    private static final int BONE_COUNT = BOARD + 16;
    private static final int BONES = BONE_COUNT + 1;

    private static final float FOV_DEGREES = 50f;
    /** Moves the view's centre right, clear of the panel (NDC units). */
    private static final float SHIFT = 0.3f;
    private static final int BACKDROP = 0xFF20242C;

    private record Mesh(ResourceLocation texture, boolean lit, boolean sky, int first, int count) {}

    private static Path parkRoot;
    private static List<Mesh> meshes = List.of();
    private static ResourceLocation lightmap;
    private static float[] vertices = new float[0];
    private static int floatsPerVertex;

    private float[] data = new float[1 << 12];
    private float[] bones = new float[0];

    /** Loads the converted set once; without it the demo plays on a plain backdrop. */
    static void loadPark(Path root) {
        if (root.equals(parkRoot)) {
            return;
        }
        parkRoot = root;
        meshes = List.of();
        lightmap = null;
        Path manifest = root.resolve("park/park.json");
        if (!Files.isRegularFile(manifest)) {
            MineSkate3.LOGGER.info("Trick Guide set not converted; demos play without it");
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            floatsPerVertex = json.get("floats_per_vertex").getAsInt();
            ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(root.resolve("park/park.bin")))
                    .order(ByteOrder.LITTLE_ENDIAN);
            float[] loaded = new float[bytes.remaining() / 4];
            bytes.asFloatBuffer().get(loaded);
            List<Mesh> list = new ArrayList<>();
            JsonArray entries = json.getAsJsonArray("meshes");
            for (int i = 0; i < entries.size(); i++) {
                JsonObject m = entries.get(i).getAsJsonObject();
                int first = m.get("first").getAsInt();
                int count = m.get("count").getAsInt();
                if ((first + count) * floatsPerVertex > loaded.length) {
                    throw new IOException("mesh " + i + " runs past park.bin");
                }
                ResourceLocation texture = upload(root, m.getAsJsonObject("texture"), "guide/park/" + i);
                if (texture != null) {
                    list.add(new Mesh(texture, m.get("lit").getAsBoolean(), m.get("sky").getAsBoolean(), first,
                            count));
                }
            }
            JsonElement lm = json.get("lightmap");
            lightmap = lm == null || lm.isJsonNull() ? null : upload(root, lm.getAsJsonObject(), "guide/park/lightmap");
            vertices = loaded;
            meshes = list;
        } catch (IOException | RuntimeException e) {
            MineSkate3.LOGGER.warn("Trick Guide set could not be loaded", e);
        }
    }

    private static ResourceLocation upload(Path root, JsonObject texture, String name) {
        ResourceLocation location = TrickHud.uploadRaw(root.resolve(texture.get("file").getAsString()),
                texture.get("width").getAsInt(), texture.get("height").getAsInt(),
                ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, name));
        if (location != null) {
            // Set surfaces tile their textures.
            Minecraft.getInstance().getTextureManager().getTexture(location).bind();
            RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        }
        return location;
    }

    /** Advances the demo by `dt` seconds and draws it over the whole screen. */
    void render(GuiGraphics graphics, long handle, float dt) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), BACKDROP);
        int length = NativeSkate.guideDemo(handle, dt, data);
        if (length < 0) {
            data = new float[-length * 2];
            length = NativeSkate.guideDemo(handle, 0f, data);
        }
        if (length < BONES || data[0] != 1f) {
            return;
        }
        graphics.flush();
        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f projection = new Matrix4f().translation(SHIFT, 0f, 0f)
                .perspective((float) Math.toRadians(FOV_DEGREES), aspect, 0.05f, 5000f);
        Matrix4f view = new Matrix4f().setLookAt(data[EYE], data[EYE + 1], data[EYE + 2], data[TARGET],
                data[TARGET + 1], data[TARGET + 2], 0f, 1f, 0f);

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        float oldFogStart = RenderSystem.getShaderFogStart();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            modelView.set(view);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.disableCull();
            drawPark();

            Matrix4f[] parts = new Matrix4f[6];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = new Matrix4f().set(data, PARTS + i * 16);
            }
            Matrix4f board = new Matrix4f().set(data, BOARD);
            int count = (int) data[BONE_COUNT];
            float[] skin = null;
            if (count > 0 && count == SkinnedMeshes.boneCount() && length >= BONES + count * 12) {
                if (bones.length != count * 12) {
                    bones = new float[count * 12];
                }
                System.arraycopy(data, BONES, bones, 0, bones.length);
                skin = bones;
            }
            Lighting.setupLevel();
            SkaterRenderer.renderDemo(parts, board, skin, new PoseStack(), mc.renderBuffers().bufferSource(),
                    LightTexture.FULL_BRIGHT);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            RenderSystem.setShaderFogStart(oldFogStart);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            Lighting.setupFor3DItems();
            // The panel draws over the scene, not into it.
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
    }

    /** Diffuse first, then the baked lightmap multiplied over the lit surfaces. */
    private static void drawPark() {
        if (meshes.isEmpty()) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(false);
        for (Mesh mesh : meshes) {
            if (mesh.sky()) {
                draw(mesh, mesh.texture(), 3);
            }
        }
        RenderSystem.depthMask(true);
        for (Mesh mesh : meshes) {
            if (!mesh.sky()) {
                draw(mesh, mesh.texture(), 3);
            }
        }
        if (lightmap != null) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
            RenderSystem.depthMask(false);
            for (Mesh mesh : meshes) {
                if (mesh.lit()) {
                    draw(mesh, lightmap, 5);
                }
            }
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /** One mesh's triangles with the UV pair at `uv` within each vertex. */
    private static void draw(Mesh mesh, ResourceLocation texture, int uv) {
        RenderSystem.setShaderTexture(0, texture);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_TEX);
        for (int v = mesh.first(); v < mesh.first() + mesh.count(); v++) {
            int p = v * floatsPerVertex;
            buffer.addVertex(vertices[p], vertices[p + 1], vertices[p + 2]).setUv(vertices[p + uv],
                    vertices[p + uv + 1]);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
