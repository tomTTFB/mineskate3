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
import net.minecraft.client.gui.Font;
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
            loaded[i] = uploadRaw(root.resolve(parts[0]), width, height,
                    ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "hud/" + i));
        }
        textures = loaded;
        texturesHandle = handle;
        return true;
    }

    private static ResourceLocation white;

    /** A 1x1 white texture, for untextured HUD geometry. */
    static ResourceLocation white() {
        if (white == null) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            white = ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "white");
            Minecraft.getInstance().getTextureManager().register(white, new DynamicTexture(image));
        }
        return white;
    }

    /** Uploads a raw RGBA file (as the HUD converters write them); null if it cannot. */
    static ResourceLocation uploadRaw(Path file, int width, int height, ResourceLocation location) {
        try {
            byte[] rgba = Files.readAllBytes(file);
            if (rgba.length != width * height * 4) {
                throw new IOException("unexpected size " + rgba.length);
            }
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
            Minecraft.getInstance().getTextureManager().register(location, texture);
            return location;
        } catch (IOException | RuntimeException e) {
            MineSkate3.LOGGER.warn("HUD texture {} could not be loaded", file, e);
            return null;
        }
    }

    /** Sets up blending and the colour-transform shader; false if it is unavailable. */
    static boolean begin(GuiGraphics graphics) {
        if (shader == null) {
            return false;
        }
        graphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        // APT geometry comes in either winding, and skate-game's 2D pass draws
        // both. With culling left on, whole shapes vanish depending on what
        // GL state the previous draw left behind.
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> shader);
        return true;
    }

    /** One textured triangle list: x, y, u, v per vertex from `data[start]`. */
    static void triangles(Matrix4f matrix, ResourceLocation texture, float[] multiply, float[] add, float[] data,
            int start, int vertices) {
        RenderSystem.setShaderTexture(0, texture);
        shader.safeGetUniform("ColorMultiply").set(multiply[0], multiply[1], multiply[2], multiply[3]);
        shader.safeGetUniform("ColorAdd").set(add[0], add[1], add[2], add[3]);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,
                DefaultVertexFormat.POSITION_TEX);
        int count = vertices - vertices % 3;
        for (int v = 0; v < count; v++) {
            int p = start + v * 4;
            buffer.addVertex(matrix, data[p], data[p + 1], 0f).setUv(data[p + 2], data[p + 3]);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    static void end() {
        shader.safeGetUniform("ColorMultiply").set(1f, 1f, 1f, 1f);
        shader.safeGetUniform("ColorAdd").set(0f, 0f, 0f, 0f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
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
        drawList(graphics, draws, length, textures, session.pad().controllerName != null);
    }

    /** Controller buttons, in the native draw list's order (hud::BUTTONS). */
    private static final String[] BUTTONS = {"A", "B", "X", "Y", "L_Trigger", "R_Trigger", "L_Bumper",
            "R_Bumper", "Stick_Left", "Stick_Right", "Stick_Right_Up", "Stick_Right_Down", "Stick_Right_L",
            "Stick_Right_R", "Start", "Back"};
    private static final String[] PAD_LABELS = {"A", "B", "X", "Y", "LT", "RT", "LB", "RB", "LS", "RS",
            "RS\u2191", "RS\u2193", "RS\u2190", "RS\u2192", "START", "BACK"};
    /** The keys PadInput maps to each button for keyboard and mouse players. */
    private static final String[] KEY_LABELS = {"Space", "R", "Shift", "F", "LMB", "RMB", "Q", "E", "WASD",
            "Mouse", "Mouse\u2191", "Mouse\u2193", "Mouse\u2190", "Mouse\u2192", "Esc", "Tab"};
    private static final int[] BUTTON_COLOURS = {0xFF4E9F3D, 0xFFC8392B, 0xFF2F6FBF, 0xFFD9A21B};

    /**
     * Draws an APT draw list (see hud::encode_draws) stretched from the
     * 1280x720 stage: texture index -1 is a solid fill, -2 - n controller
     * button n, which the original engine draws natively; here a labelled badge.
     */
    static void drawList(GuiGraphics graphics, float[] draws, int length, ResourceLocation[] textures,
            boolean controller) {
        if (!begin(graphics)) {
            return;
        }
        float sx = graphics.guiWidth() / STAGE_WIDTH;
        float sy = graphics.guiHeight() / STAGE_HEIGHT;
        Matrix4f matrix = new Matrix4f(graphics.pose().last().pose()).scale(sx, sy, 1f);
        float[] multiply = new float[4];
        float[] add = new float[4];
        java.util.List<float[]> buttons = new java.util.ArrayList<>();
        int at = 0;
        while (at + 10 <= length) {
            int texture = (int) draws[at];
            int vertices = (int) draws[at + 1];
            int start = at + 10;
            int next = start + vertices * 4;
            if (next > length) {
                break;
            }
            System.arraycopy(draws, at + 2, multiply, 0, 4);
            System.arraycopy(draws, at + 6, add, 0, 4);
            if (texture <= -2 && vertices >= 3) {
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                for (int v = 0; v < vertices; v++) {
                    x0 = Math.min(x0, draws[start + v * 4]);
                    x1 = Math.max(x1, draws[start + v * 4]);
                    y0 = Math.min(y0, draws[start + v * 4 + 1]);
                    y1 = Math.max(y1, draws[start + v * 4 + 1]);
                }
                buttons.add(new float[] {-2 - texture, x0 * sx, y0 * sy, x1 * sx, y1 * sy, multiply[3]});
            } else if (texture == -1 && vertices >= 3) {
                triangles(matrix, white(), multiply, add, draws, start, vertices);
            } else if (texture >= 0 && texture < textures.length && textures[texture] != null && vertices >= 3) {
                triangles(matrix, textures[texture], multiply, add, draws, start, vertices);
            }
            at = next;
        }
        end();
        for (float[] b : buttons) {
            badge(graphics, (int) b[0], b[1], b[2], b[3], b[4], b[5], controller);
        }
    }

    private static void badge(GuiGraphics graphics, int button, float x0, float y0, float x1, float y1,
            float alpha, boolean controller) {
        if (button < 0 || button >= BUTTONS.length || alpha <= 0.05f) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String label = (controller ? PAD_LABELS : KEY_LABELS)[button];
        int colour = button < BUTTON_COLOURS.length ? BUTTON_COLOURS[button] : 0xFF2A2A2A;
        float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        float height = Math.max(4f, y1 - y0);
        float scale = Math.min(1.5f, height / 11f);
        float width = Math.max(height, font.width(label) * scale + 4 * scale);
        int left = Math.round(cx - width / 2), right = Math.round(cx + width / 2);
        int top = Math.round(cy - height / 2), bottom = Math.round(cy + height / 2);
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF101010);
        graphics.fill(left, top, right, bottom, colour);
        graphics.pose().pushPose();
        graphics.pose().translate(cx - font.width(label) * scale / 2f, cy - 4 * scale, 0);
        graphics.pose().scale(scale, scale, 1f);
        graphics.drawString(font, label, 0, 0, 0xFFFFFFFF, false);
        graphics.pose().popPose();
    }
}
