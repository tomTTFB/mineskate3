package dev.mineskate3.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SkateData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;

/**
 * Draws Skate 3's original trick display. The retail APT movie runs in the
 * native session next to the scoring it reads; this class only rasterises its
 * draw list (textured triangles with the movie's colour transforms) over the
 * screen, stretched from the movie's 1280x720 stage like the original.
 */
public final class TrickHud {
    private static final float STAGE_WIDTH = 1280f;
    private static final float STAGE_HEIGHT = 720f;

    private static ShaderInstance shader;
    private static long texturesHandle;
    private static ResourceLocation[] textures;
    private static float[] draws = new float[1 << 16];

    private TrickHud() {}

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "hud"), DefaultVertexFormat.POSITION_TEX),
                    loaded -> shader = loaded);
        } catch (IOException e) {
            MineSkate3.LOGGER.error("Trick HUD shader failed to load", e);
        }
    }

    /** Uploads the HUD atlases once per native session. Raw RGBA files from the converter. */
    private static boolean loadTextures(long handle) {
        if (texturesHandle == handle && textures != null) {
            return true;
        }
        String[] entries = NativeSkate.hudTextures(handle);
        if (entries == null) {
            return false;
        }
        Path root = SkateData.assets().resolve("private").resolve("hud");
        ResourceLocation[] loaded = new ResourceLocation[entries.length];
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|");
            int width = Integer.parseInt(parts[1]);
            int height = Integer.parseInt(parts[2]);
            try {
                byte[] rgba = Files.readAllBytes(root.resolve(parts[0]));
                NativeImage image = new NativeImage(width, height, false);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int at = (y * width + x) * 4;
                        // NativeImage packs ABGR.
                        image.setPixelRGBA(x, y, (rgba[at + 3] & 0xFF) << 24 | (rgba[at + 2] & 0xFF) << 16
                                | (rgba[at + 1] & 0xFF) << 8 | (rgba[at] & 0xFF));
                    }
                }
                DynamicTexture texture = new DynamicTexture(image);
                texture.setFilter(true, false);
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "hud/" + i);
                mc.getTextureManager().register(location, texture);
                loaded[i] = location;
            } catch (IOException | RuntimeException e) {
                MineSkate3.LOGGER.warn("Trick HUD texture {} could not be loaded", parts[0], e);
            }
        }
        textures = loaded;
        texturesHandle = handle;
        return true;
    }

    public static void render(GuiGraphics graphics) {
        SkateSession session = SkateSession.get();
        long handle = session.handle();
        if (!session.active() || handle == 0 || shader == null || NativeSkate.hudStatus(handle) != 1) {
            return;
        }
        if (!loadTextures(handle)) {
            return;
        }
        int length = NativeSkate.hudDraws(handle, draws);
        if (length < 0) {
            draws = new float[-length * 2];
            length = NativeSkate.hudDraws(handle, draws);
        }
        if (length <= 0) {
            return;
        }
        graphics.flush();
        float sx = graphics.guiWidth() / STAGE_WIDTH;
        float sy = graphics.guiHeight() / STAGE_HEIGHT;
        Matrix4f matrix = new Matrix4f(graphics.pose().last().pose()).scale(sx, sy, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(() -> shader);
        int at = 0;
        while (at + 10 <= length) {
            int texture = (int) draws[at];
            int vertices = (int) draws[at + 1];
            int start = at + 10;
            int end = start + vertices * 4;
            if (end > length) {
                break;
            }
            if (texture >= 0 && texture < textures.length && textures[texture] != null && vertices >= 3) {
                RenderSystem.setShaderTexture(0, textures[texture]);
                shader.safeGetUniform("ColorMultiply").set(draws[at + 2], draws[at + 3], draws[at + 4], draws[at + 5]);
                shader.safeGetUniform("ColorAdd").set(draws[at + 6], draws[at + 7], draws[at + 8], draws[at + 9]);
                BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                        DefaultVertexFormat.POSITION_TEX);
                int count = vertices - vertices % 3;
                for (int v = 0; v < count; v++) {
                    int p = start + v * 4;
                    buffer.addVertex(matrix, draws[p], draws[p + 1], 0f).setUv(draws[p + 2], draws[p + 3]);
                }
                BufferUploader.drawWithShader(buffer.buildOrThrow());
            }
            at = end;
        }
        shader.safeGetUniform("ColorMultiply").set(1f, 1f, 1f, 1f);
        shader.safeGetUniform("ColorAdd").set(0f, 0f, 0f, 0f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
