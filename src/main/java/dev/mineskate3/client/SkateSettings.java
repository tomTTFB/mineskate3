package dev.mineskate3.client;

import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SkateData;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Client preferences, in <game dir>/mineskate3/settings.properties. */
public final class SkateSettings {
    private static boolean loaded;
    private static boolean skate3Skater = true;
    private static boolean sounds = true;

    private SkateSettings() {}

    private static Path file() {
        return SkateData.root().resolve("settings.properties");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
            skate3Skater = !"minecraft".equals(properties.getProperty("skater", "skate3"));
            sounds = !"false".equals(properties.getProperty("sounds", "true"));
        } catch (IOException e) {
            MineSkate3.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        Properties properties = new Properties();
        properties.setProperty("skater", skate3Skater ? "skate3" : "minecraft");
        properties.setProperty("sounds", Boolean.toString(sounds));
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file())) {
                properties.store(writer, "MineSkate 3 settings");
            }
        } catch (IOException e) {
            MineSkate3.LOGGER.warn("Could not save {}", file(), e);
        }
    }

    /** Draw skaters as the Skate 3 skater (true) or the Minecraft player model with your skin. */
    public static boolean skate3Skater() {
        load();
        return skate3Skater;
    }

    public static void setSkate3Skater(boolean value) {
        load();
        skate3Skater = value;
        save();
    }

    public static boolean sounds() {
        load();
        return sounds;
    }

    public static void setSounds(boolean value) {
        load();
        sounds = value;
        save();
    }
}
