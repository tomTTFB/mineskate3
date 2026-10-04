package dev.mineskate3.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** A one-line status readout while skating. */
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
            String controller = session.pad().controllerName != null ? session.pad().controllerName
                    : "keyboard + mouse";
            text = String.format("Skate 3 | %s | %.0f km/h | %s", session.state(), speed, controller);
        }
        graphics.drawString(font, text, 6, 6, 0xFFFFFF, true);
    }
}
