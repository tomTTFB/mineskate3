package dev.mineskate3.client;

import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SkateData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * Skate 3's Trick Guide: the original menu movie, run by the native engine
 * and drawn over the world. Up/down browse, select opens a category, back
 * returns and closes from the top level with the movie's own outro.
 */
public final class TrickGuideScreen extends Screen {
    private static final double FRAME_SECONDS = 1.0 / 30.0;
    private static final int NAV_UP = 0;
    private static final int NAV_DOWN = 1;
    private static final int NAV_SELECT = 2;
    private static final int NAV_BACK = 3;
    /** Stick deflection that counts as a menu step, and the held-key repeat. */
    private static final int STICK_STEP = 20000;
    private static final double REPEAT_DELAY = 0.4;
    private static final double REPEAT_INTERVAL = 0.11;

    private static String[] textureKey;
    private static ResourceLocation[] textures;

    private long handle;
    private float[] draws = new float[1 << 16];
    private final PadInput pad = new PadInput();
    private int lastButtons;
    private int heldDirection = -1;
    private double heldTime;
    private long lastFrame = System.nanoTime();
    private double accumulated;
    private String error;

    private TrickGuideScreen(long handle) {
        super(Component.translatable("screen.mineskate3.trick_guide"));
        this.handle = handle;
    }

    static Path folder() {
        return SkateData.assets().resolve("private").resolve("trickguide");
    }

    static boolean available() {
        Path root = folder();
        return Files.isRegularFile(root.resolve("runtime/trickguide.json"))
                && Files.isRegularFile(root.resolve("menu.json"));
    }

    /** Opens the guide, or says in chat why it cannot. */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (!available()) {
            message(mc, "Trick Guide not converted: run the converter with --trick-guide-only");
            return;
        }
        try {
            if (NativeSkate.library() == null) {
                NativeSkate.load(SkateData.root().resolve("natives"));
            }
            long handle = NativeSkate.guideOpen(folder().toString(), true);
            if (!loadTextures(handle)) {
                NativeSkate.guideFree(handle);
                message(mc, "Trick Guide textures could not be loaded");
                return;
            }
            NativeSkate.guideUpdate(handle);
            mc.setScreen(new TrickGuideScreen(handle));
        } catch (Exception | UnsatisfiedLinkError e) {
            MineSkate3.LOGGER.error("Trick Guide failed to open", e);
            message(mc, "Trick Guide failed: " + e.getMessage());
        }
    }

    private static void message(Minecraft mc, String text) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(text), false);
        }
    }

    /** The guide's atlases, uploaded once per game run. */
    private static boolean loadTextures(long handle) {
        String[] entries = NativeSkate.guideTextures(handle);
        if (entries == null) {
            return false;
        }
        if (textures != null && Arrays.equals(entries, textureKey)) {
            return true;
        }
        Path root = folder();
        ResourceLocation[] loaded = new ResourceLocation[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split("\\|");
            loaded[i] = TrickHud.uploadRaw(root.resolve(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "guide/" + i));
        }
        textures = loaded;
        textureKey = entries;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // The world stays visible behind the panel, as the demo plays there.
    }

    private void navigate(int nav) {
        if (handle != 0 && error == null && !NativeSkate.guideInput(handle, nav)) {
            fail();
        }
    }

    private void fail() {
        error = NativeSkate.guideError(handle);
        MineSkate3.LOGGER.error("Trick Guide stopped: {}", error);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> navigate(NAV_UP);
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> navigate(NAV_DOWN);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_RIGHT,
                    GLFW.GLFW_KEY_D -> navigate(NAV_SELECT);
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A,
                    GLFW.GLFW_KEY_R -> {
                if (error != null) {
                    onClose();
                } else {
                    navigate(NAV_BACK);
                }
            }
            default -> {
                if (MineSkate3Client.TRICK_GUIDE.matches(key, scanCode) && handle != 0) {
                    NativeSkate.guideClose(handle);
                }
                return super.keyPressed(key, scanCode, modifiers);
            }
        }
        return true;
    }

    /** Controller: D-pad or left stick to browse with repeat, A select, B back. */
    private void pollController(float dt) {
        pad.poll(dt, false);
        if (pad.controllerName == null) {
            lastButtons = 0;
            heldDirection = -1;
            return;
        }
        int pressed = pad.buttons & ~lastButtons;
        lastButtons = pad.buttons;
        if ((pressed & PadInput.A) != 0) {
            navigate(NAV_SELECT);
        }
        if ((pressed & (PadInput.B | PadInput.BACK)) != 0) {
            navigate(NAV_BACK);
        }
        int direction = -1;
        if ((pad.buttons & PadInput.DPAD_UP) != 0 || pad.ly > STICK_STEP) {
            direction = NAV_UP;
        } else if ((pad.buttons & PadInput.DPAD_DOWN) != 0 || pad.ly < -STICK_STEP) {
            direction = NAV_DOWN;
        }
        if (direction != heldDirection) {
            heldDirection = direction;
            heldTime = 0;
            if (direction >= 0) {
                navigate(direction);
            }
        } else if (direction >= 0) {
            heldTime += dt;
            if (heldTime >= REPEAT_DELAY) {
                heldTime -= REPEAT_INTERVAL;
                navigate(direction);
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float dt = (float) Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        if (handle == 0) {
            return;
        }
        pollController(dt);
        accumulated = Math.min(accumulated + dt, 0.25);
        while (error == null && accumulated >= FRAME_SECONDS) {
            accumulated -= FRAME_SECONDS;
            if (!NativeSkate.guideUpdate(handle)) {
                fail();
            }
        }
        if (error == null && NativeSkate.guideClosed(handle)) {
            onClose();
            return;
        }
        int length = NativeSkate.guideDraws(handle, draws);
        if (length < 0) {
            draws = new float[-length * 2];
            length = NativeSkate.guideDraws(handle, draws);
        }
        if (length > 0) {
            TrickHud.drawList(graphics, draws, length, textures, pad.controllerName != null);
        }
        if (error != null) {
            graphics.drawString(font, "Trick Guide stopped: " + error, 6, 6, 0xFF8080, true);
            graphics.drawString(font, "Press Esc to close", 6, 18, 0xFFFFFF, true);
        }
    }

    @Override
    public void removed() {
        if (handle != 0) {
            NativeSkate.guideFree(handle);
            handle = 0;
        }
    }
}
