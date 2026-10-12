package dev.mineskate3.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A one-line status readout: while loading, while the original trick HUD is
 * unavailable (with how to add it), or with F3 open.
 */
final class SkateHud {
    private SkateHud() {}

    static void render(GuiGraphics graphics) {
        SkateSession session = SkateSession.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || (!session.active() && !session.entering())) {
            return;
        }
        Font font = mc.font;
        String text;
        if (session.entering()) {
            text = "Skate 3: loading...";
        } else {
            float[] p = session.pose();
            double speed = Math.sqrt(p[NativeSkate.VELOCITY] * p[NativeSkate.VELOCITY]
                    + p[NativeSkate.VELOCITY + 1] * p[NativeSkate.VELOCITY + 1]
                    + p[NativeSkate.VELOCITY + 2] * p[NativeSkate.VELOCITY + 2]) * 3.6;
            PadInput pad = session.pad();
            String controller = pad.controllerName != null ? pad.controllerName
                    : pad.unrecognisedName != null ? "keyboard + mouse (" + pad.unrecognisedName + " not recognised)"
                    : "keyboard + mouse";
            int hud = session.handle() != 0 ? NativeSkate.hudStatus(session.handle()) : 0;
            boolean debug = mc.gui.getDebugOverlay().showDebugScreen();
            if (hud == 1 && !debug) {
                return;
            }
            text = String.format("Skate 3 | %s | %.0f km/h | %s", session.state(), speed, controller);
            if (debug) {
                int st = NativeSkate.STATUS;
                int b = pad.buttons;
                graphics.drawString(font, String.format(
                        "Marker | pad LB %s, up %s, down %s | panel %s, can place %s, can return %s | placed %.0f, returned %.0f",
                        yes(b & PadInput.LEFT_SHOULDER), yes(b & PadInput.DPAD_UP), yes(b & PadInput.DPAD_DOWN),
                        yes(p[st + 4]), yes(p[st + 5]), yes(p[st + 6]), p[st + 8], p[st + 9]),
                        6, 30, 0xFFFFFF, true);
            }
            if (hud == 0) {
                graphics.drawString(font, "Trick HUD not installed: Mods > MineSkate 3 > Config to add it", 6, 18,
                        0xFFFF80, true);
            } else if (hud < 0) {
                graphics.drawString(font, "Trick HUD stopped: " + NativeSkate.hudError(session.handle()), 6, 18,
                        0xFF8080, true);
            }
        }
        graphics.drawString(font, text, 6, 6, 0xFFFFFF, true);
    }

    private static String yes(float value) {
        return value != 0f ? "yes" : "no";
    }
}
