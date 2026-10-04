package dev.mineskate3.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SkateData;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryUtil;

/**
 * Skate reads an Xbox 360 pad: button bits, trigger bytes and signed stick
 * axes, y up. Controllers are tried in this order:
 * <ol>
 * <li>XInput, through the engine's own raw transport (Windows): Xbox pads and
 * anything Steam Input presents as one, exactly as Skate 3 reads them;</li>
 * <li>GLFW gamepads, with SDL_GameControllerDB mappings bundled so PlayStation,
 * Switch and generic pads are recognised on every platform;</li>
 * <li>keyboard and mouse, with mouse movement as the right stick so flick-it
 * tricks still work.</li>
 * </ol>
 */
public final class PadInput {
    // XInput button bits.
    public static final int DPAD_UP = 0x0001;
    public static final int DPAD_DOWN = 0x0002;
    public static final int DPAD_LEFT = 0x0004;
    public static final int DPAD_RIGHT = 0x0008;
    public static final int START = 0x0010;
    public static final int BACK = 0x0020;
    public static final int LEFT_THUMB = 0x0040;
    public static final int RIGHT_THUMB = 0x0080;
    public static final int LEFT_SHOULDER = 0x0100;
    public static final int RIGHT_SHOULDER = 0x0200;
    public static final int A = 0x1000;
    public static final int B = 0x2000;
    public static final int X = 0x4000;
    public static final int Y = 0x8000;

    /** Mouse speed, in pixels per second, that pushes the right stick fully over. */
    private static final double MOUSE_FULL_SPEED = 900.0;
    /** Smoothing time constant for the mouse stick, in seconds. */
    private static final double MOUSE_SMOOTHING = 0.035;

    public boolean connected;
    public int buttons;
    public int leftTrigger;
    public int rightTrigger;
    public int lx;
    public int ly;
    public int rx;
    public int ry;
    /** Name of the controller in use, or null for keyboard and mouse. */
    public String controllerName;
    /** A joystick GLFW sees but has no gamepad mapping for, if any. */
    public String unrecognisedName;

    private final GLFWGamepadState state = GLFWGamepadState.create();
    private final int[] xinput = new int[7];
    private static boolean mappingsLoaded;
    private static boolean nativeTried;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private double mouseStickX;
    private double mouseStickY;

    private void clear() {
        connected = true;
        buttons = 0;
        leftTrigger = 0;
        rightTrigger = 0;
        lx = ly = rx = ry = 0;
    }

    /** Polls once per rendered frame. `blocked` sends a centred pad (menus, chat). */
    public void poll(float dt, boolean blocked) {
        clear();
        controllerName = null;
        unrecognisedName = null;
        if (readXInput(blocked)) {
            lastMouseX = Double.NaN;
            return;
        }
        int pad = findGamepad();
        if (pad >= 0) {
            controllerName = GLFW.glfwGetGamepadName(pad);
            if (!blocked) {
                readGamepad(pad);
            }
            lastMouseX = Double.NaN;
            return;
        }
        unrecognisedName = findUnrecognised();
        if (blocked) {
            lastMouseX = Double.NaN;
            mouseStickX = mouseStickY = 0;
            return;
        }
        readKeyboardAndMouse(dt);
    }

    /** The engine library carries the XInput reader; load it early so pads work before the first J. */
    private static boolean nativeReady() {
        if (NativeSkate.library() != null) {
            return true;
        }
        if (nativeTried) {
            return false;
        }
        nativeTried = true;
        try {
            NativeSkate.load(SkateData.root().resolve("natives"));
            return true;
        } catch (Exception e) {
            MineSkate3.LOGGER.warn("No native controller input: {}", e.getMessage());
            return false;
        }
    }

    private boolean readXInput(boolean blocked) {
        if (!NativeSkate.platform().startsWith("windows") || !nativeReady()) {
            return false;
        }
        int slot = NativeSkate.pollXInput(xinput);
        if (slot < 0) {
            return false;
        }
        controllerName = "Xbox controller " + (slot + 1) + " (XInput)";
        if (!blocked) {
            buttons = xinput[0] & 0xFFFF;
            leftTrigger = xinput[1];
            rightTrigger = xinput[2];
            lx = xinput[3];
            ly = xinput[4];
            rx = xinput[5];
            ry = xinput[6];
        }
        return true;
    }

    /** Adds the bundled SDL_GameControllerDB mappings to GLFW's built-in ones (main thread). */
    private static void loadMappings() {
        if (mappingsLoaded) {
            return;
        }
        mappingsLoaded = true;
        try (InputStream in = PadInput.class.getResourceAsStream("/mineskate3/controllers/gamecontrollerdb.txt")) {
            if (in == null) {
                return;
            }
            byte[] text = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(text.length + 1);
            try {
                buffer.put(text).put((byte) 0).flip();
                if (!GLFW.glfwUpdateGamepadMappings(buffer)) {
                    MineSkate3.LOGGER.warn("GLFW rejected some controller mappings");
                }
            } finally {
                MemoryUtil.memFree(buffer);
            }
        } catch (Exception e) {
            MineSkate3.LOGGER.warn("Controller mappings could not be loaded", e);
        }
    }

    private static int findGamepad() {
        loadMappings();
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid) && GLFW.glfwJoystickIsGamepad(jid)) {
                return jid;
            }
        }
        return -1;
    }

    private static String findUnrecognised() {
        for (int jid = GLFW.GLFW_JOYSTICK_1; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid) && !GLFW.glfwJoystickIsGamepad(jid)) {
                String name = GLFW.glfwGetJoystickName(jid);
                return name != null ? name : "joystick " + (jid + 1);
            }
        }
        return null;
    }

    /** Toggle chord on a controller: both sticks clicked, as in the mashup. */
    public boolean toggleChordHeld() {
        int chord = LEFT_THUMB | RIGHT_THUMB;
        if (NativeSkate.platform().startsWith("windows") && nativeReady() && NativeSkate.pollXInput(xinput) >= 0) {
            return (xinput[0] & chord) == chord;
        }
        int pad = findGamepad();
        if (pad < 0 || !GLFW.glfwGetGamepadState(pad, state)) {
            return false;
        }
        return state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB) == GLFW.GLFW_PRESS
                && state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB) == GLFW.GLFW_PRESS;
    }

    private void readGamepad(int pad) {
        if (!GLFW.glfwGetGamepadState(pad, state)) {
            return;
        }
        int[][] map = {
            {GLFW.GLFW_GAMEPAD_BUTTON_A, A},
            {GLFW.GLFW_GAMEPAD_BUTTON_B, B},
            {GLFW.GLFW_GAMEPAD_BUTTON_X, X},
            {GLFW.GLFW_GAMEPAD_BUTTON_Y, Y},
            {GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, LEFT_SHOULDER},
            {GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, RIGHT_SHOULDER},
            {GLFW.GLFW_GAMEPAD_BUTTON_BACK, BACK},
            {GLFW.GLFW_GAMEPAD_BUTTON_START, START},
            {GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB, LEFT_THUMB},
            {GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB, RIGHT_THUMB},
            {GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP, DPAD_UP},
            {GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN, DPAD_DOWN},
            {GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT, DPAD_LEFT},
            {GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, DPAD_RIGHT},
        };
        for (int[] m : map) {
            if (state.buttons(m[0]) == GLFW.GLFW_PRESS) {
                buttons |= m[1];
            }
        }
        // GLFW sticks are y down; XInput's are y up. Triggers run -1..1.
        lx = axis(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
        ly = axis(-state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));
        rx = axis(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X));
        ry = axis(-state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));
        leftTrigger = trigger(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER));
        rightTrigger = trigger(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER));
    }

    private static int axis(float v) {
        return Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
    }

    private static int trigger(float v) {
        return Math.round(Math.max(0f, Math.min(1f, (v + 1f) * 0.5f)) * 255f);
    }

    private static boolean key(long window, int key) {
        return InputConstants.isKeyDown(window, key);
    }

    private static boolean mouse(long window, int button) {
        return GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS;
    }

    private void readKeyboardAndMouse(float dt) {
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();

        double x = (key(window, GLFW.GLFW_KEY_D) ? 1 : 0) - (key(window, GLFW.GLFW_KEY_A) ? 1 : 0);
        double y = (key(window, GLFW.GLFW_KEY_W) ? 1 : 0) - (key(window, GLFW.GLFW_KEY_S) ? 1 : 0);
        double length = Math.hypot(x, y);
        if (length > 1) {
            x /= length;
            y /= length;
        }
        lx = axis((float) x);
        ly = axis((float) y);

        if (key(window, GLFW.GLFW_KEY_SPACE)) buttons |= A;
        if (key(window, GLFW.GLFW_KEY_R)) buttons |= B;
        if (key(window, GLFW.GLFW_KEY_LEFT_SHIFT)) buttons |= X;
        if (key(window, GLFW.GLFW_KEY_F)) buttons |= Y;
        if (key(window, GLFW.GLFW_KEY_Q)) buttons |= LEFT_SHOULDER;
        if (key(window, GLFW.GLFW_KEY_E)) buttons |= RIGHT_SHOULDER;
        if (key(window, GLFW.GLFW_KEY_TAB)) buttons |= BACK;
        if (key(window, GLFW.GLFW_KEY_C)) buttons |= LEFT_THUMB;
        if (key(window, GLFW.GLFW_KEY_V)) buttons |= RIGHT_THUMB;
        if (key(window, GLFW.GLFW_KEY_UP)) buttons |= DPAD_UP;
        if (key(window, GLFW.GLFW_KEY_DOWN)) buttons |= DPAD_DOWN;
        if (key(window, GLFW.GLFW_KEY_LEFT)) buttons |= DPAD_LEFT;
        if (key(window, GLFW.GLFW_KEY_RIGHT)) buttons |= DPAD_RIGHT;
        leftTrigger = mouse(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) ? 255 : 0;
        rightTrigger = mouse(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) ? 255 : 0;

        // Mouse velocity drives the right stick: a quick pull down then up
        // is an ollie, as a flick of the stick would be.
        double mx = mc.mouseHandler.xpos();
        double my = mc.mouseHandler.ypos();
        double targetX = 0;
        double targetY = 0;
        if (!Double.isNaN(lastMouseX) && dt > 0) {
            targetX = (mx - lastMouseX) / dt / MOUSE_FULL_SPEED;
            targetY = -(my - lastMouseY) / dt / MOUSE_FULL_SPEED;
        }
        lastMouseX = mx;
        lastMouseY = my;
        double blend = 1 - Math.exp(-dt / MOUSE_SMOOTHING);
        mouseStickX += (targetX - mouseStickX) * blend;
        mouseStickY += (targetY - mouseStickY) * blend;
        double stick = Math.hypot(mouseStickX, mouseStickY);
        double scale = stick > 1 ? 1 / stick : 1;
        rx = axis((float) (mouseStickX * scale));
        ry = axis((float) (mouseStickY * scale));
    }
}
