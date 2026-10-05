package dev.mineskate3.client.setup;

import dev.mineskate3.MineSkate3;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import dev.mineskate3.client.SkateSettings;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** First-run setup: pick default.xex, convert it with the bundled converter. */
public final class SetupScreen extends Screen {
    private static final int MAX_LOG = 12;

    private final Screen parent;
    private final List<String> log = new ArrayList<>();
    private EditBox path;
    private Button browse;
    private Button convert;
    private Button install;
    private volatile ConverterRunner.Python python;
    private volatile boolean probing;
    private volatile boolean busy;
    private volatile String status = "";
    private String typedPath = "";
    /** Skating data is already converted; only the trick HUD is missing. */
    private final boolean hudOnly = SkateData.ready() && !SkateData.hudReady();

    public SetupScreen(Screen parent) {
        super(Component.translatable("mineskate3.setup.title"));
        this.parent = parent;
    }

    private synchronized void log(String line) {
        log.add(line);
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
        MineSkate3.LOGGER.info("[converter] {}", line);
    }

    private synchronized List<String> logLines() {
        return new ArrayList<>(log);
    }

    @Override
    protected void init() {
        int w = Math.min(400, this.width - 40);
        int x = (this.width - w) / 2;
        int y = 92;
        path = new EditBox(this.font, x, y, w - 84, 20, Component.literal("default.xex"));
        path.setMaxLength(4096);
        path.setHint(Component.literal("Path to Skate 3 default.xex"));
        path.setValue(typedPath);
        path.setResponder(value -> typedPath = value);
        addRenderableWidget(path);
        browse = addRenderableWidget(Button.builder(Component.literal("Browse..."), b -> browse())
                .bounds(x + w - 80, y, 80, 20).build());
        convert = addRenderableWidget(Button.builder(Component.literal(hudOnly ? "Add trick HUD" : "Convert"),
                        b -> startConvert())
                .bounds(x, y + 26, (w - 8) / 2, 20).build());
        install = addRenderableWidget(Button.builder(Component.literal("Install numpy + Pillow"), b -> startInstall())
                .bounds(x + (w + 8) / 2, y + 26, (w - 8) / 2, 20).build());
        int bw = Math.min(140, (w - 16) / 3);
        int row = this.width / 2 - bw - bw / 2 - 8;
        addRenderableWidget(Button.builder(skaterLabel(), b -> {
            SkateSettings.setSkate3Skater(!SkateSettings.skate3Skater());
            b.setMessage(skaterLabel());
        }).bounds(row, this.height - 52, bw, 20).build());
        addRenderableWidget(Button.builder(soundLabel(), b -> {
            SkateSettings.setSounds(!SkateSettings.sounds());
            b.setMessage(soundLabel());
        }).bounds(row + bw + 8, this.height - 52, bw, 20).build());
        addRenderableWidget(Button.builder(rumbleLabel(), b -> {
            SkateSettings.setRumble(!SkateSettings.rumble());
            b.setMessage(rumbleLabel());
        }).bounds(row + 2 * (bw + 8), this.height - 52, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds((this.width - 120) / 2, this.height - 28, 120, 20).build());
        if (python == null && !probing) {
            probePython();
        }
        refreshButtons();
    }

    private static Component skaterLabel() {
        return Component.literal("Skater: " + (SkateSettings.skate3Skater() ? "Skate 3 model" : "Minecraft skin"));
    }

    private static Component soundLabel() {
        return Component.literal("Sounds: " + (SkateSettings.sounds() ? "on" : "off"));
    }

    private static Component rumbleLabel() {
        return Component.literal("Rumble: " + (SkateSettings.rumble() ? "on" : "off"));
    }

    private void refreshButtons() {
        boolean havePython = python != null;
        convert.active = !busy && havePython && python.hasPackages() && !typedPath.isBlank();
        install.visible = havePython && !python.hasPackages();
        install.active = !busy;
        browse.active = !busy;
    }

    private void probePython() {
        probing = true;
        status = "Looking for Python...";
        Thread thread = new Thread(() -> {
            ConverterRunner.Python found = ConverterRunner.findPython();
            python = found;
            probing = false;
            if (found == null) {
                status = "Python 3.11 or newer was not found. Install it from python.org, then reopen this screen.";
            } else if (!found.hasPackages()) {
                status = "Python " + found.version() + " found, but it needs numpy and Pillow.";
            } else if (hudOnly) {
                status = "Python " + found.version() + " ready. Select your default.xex again to add the trick HUD.";
            } else if (SkateData.ready()) {
                status = "Skate 3 data is installed. Convert again only to refresh it.";
            } else {
                status = "Python " + found.version() + " ready. Select your default.xex.";
            }
        }, "mineskate3-python-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private void browse() {
        Runnable pick = () -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("default.xex"));
                filters.flip();
                String chosen = TinyFileDialogs.tinyfd_openFileDialog(
                        "Select Skate 3 default.xex", "", filters, "Skate 3 default.xex", false);
                if (chosen != null && this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        typedPath = chosen;
                        if (path != null) {
                            path.setValue(chosen);
                        }
                    });
                }
            } catch (Throwable t) {
                status = "No file picker available here: paste the path to default.xex instead.";
                MineSkate3.LOGGER.warn("File dialog failed", t);
            }
        };
        // macOS dialogs must open on the main thread; elsewhere keep the game drawing.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            pick.run();
        } else {
            Thread thread = new Thread(pick, "mineskate3-file-dialog");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void startInstall() {
        ConverterRunner.Python current = python;
        if (current == null || busy) {
            return;
        }
        busy = true;
        status = "Installing numpy and Pillow...";
        Thread thread = new Thread(() -> {
            boolean ok = ConverterRunner.installPackages(current, this::log);
            busy = false;
            if (ok) {
                probePython();
            } else {
                status = "pip could not install numpy and Pillow. See the log above.";
            }
        }, "mineskate3-pip");
        thread.setDaemon(true);
        thread.start();
    }

    private void startConvert() {
        ConverterRunner.Python current = python;
        String chosen = typedPath.trim();
        if (chosen.startsWith("\"") && chosen.endsWith("\"") && chosen.length() > 1) {
            chosen = chosen.substring(1, chosen.length() - 1);
        }
        if (current == null || busy || chosen.isEmpty()) {
            return;
        }
        Path xex = Path.of(chosen);
        if (xex.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".iso")) {
            status = "ISO files do not work: extract the disc and select its default.xex.";
            return;
        }
        if (!Files.isRegularFile(xex)) {
            status = "That file does not exist.";
            return;
        }
        busy = true;
        status = hudOnly ? "Adding the trick HUD..."
                : "Converting Skate 3 data. The first conversion can take a few minutes...";
        boolean onlyHud = hudOnly;
        Thread thread = new Thread(() -> {
            boolean ok = ConverterRunner.convert(current, xex, onlyHud, this::log);
            busy = false;
            if (ok && onlyHud) {
                status = "Trick HUD added. Toggle skate mode off and on (or rejoin) to see it.";
            } else if (ok) {
                status = SkateData.hudReady() ? "Skate 3 data ready. Close this screen and press J to skate."
                        : "Skate 3 data ready (without the trick HUD, see the log). Press J to skate.";
            } else {
                status = "Conversion failed. See the log above and logs/latest.log.";
            }
        }, "mineskate3-convert");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void tick() {
        refreshButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int w = Math.min(400, this.width - 40);
        int x = (this.width - w) / 2;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        Component intro = Component.literal("Skate mode runs on Skate 3's own physics, tricks and grinds, using data from "
                + "your own copy of Skate 3 (Xbox 360). Select the default.xex from your extracted game folder, with "
                + "its data folder beside it. Nothing from the game is included with this mod.");
        int y = 34;
        for (FormattedCharSequence line : this.font.split(intro, w)) {
            graphics.drawString(this.font, line, x, y, 0xD0D0D0, false);
            y += 10;
        }
        int sy = 146;
        for (FormattedCharSequence line : this.font.split(Component.literal(status), w)) {
            graphics.drawString(this.font, line, x, sy, 0xFFFF80, false);
            sy += 10;
        }
        int ly = sy + 6;
        for (String line : logLines()) {
            String clipped = this.font.plainSubstrByWidth(line, w);
            graphics.drawString(this.font, clipped, x, ly, 0xA0A0A0, false);
            ly += 10;
            if (ly > this.height - 64) {
                break;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !busy;
    }

    @Override
    public void onClose() {
        if (busy) {
            return;
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
