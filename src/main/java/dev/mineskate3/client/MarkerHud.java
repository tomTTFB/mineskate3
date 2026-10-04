package dev.mineskate3.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.client.setup.SkateData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Skate 3's session-marker display (its phone-list "object dropper" panel),
 * extracted from the player's disc by the converter's HUD step. Shown while
 * the marker modifier is held, as in skate-game: hold LB, then D-pad down to
 * place a marker or hold D-pad up to return to it.
 */
public final class MarkerHud {
    private record Primitive(ResourceLocation texture, float[] color, float[] vertices) {}

    private static List<Primitive> primitives;
    private static boolean failed;

    private MarkerHud() {}

    public static boolean available() {
        return Files.isRegularFile(folder().resolve("hud.json"));
    }

    private static Path folder() {
        return SkateData.assets().resolve("private").resolve("session-marker");
    }

    private static boolean load() {
        if (primitives != null) {
            return true;
        }
        if (failed || !available()) {
            return false;
        }
        try {
            Path folder = folder();
            JsonObject hud = JsonParser.parseString(Files.readString(folder.resolve("hud.json"))).getAsJsonObject();
            JsonArray canvas = hud.getAsJsonArray("canvas");
            if (hud.get("version").getAsInt() != 1 || canvas.get(0).getAsFloat() != 1280f
                    || canvas.get(1).getAsFloat() != 720f) {
                throw new IllegalStateException("unsupported marker HUD manifest");
            }
            List<ResourceLocation> textures = new ArrayList<>();
            JsonArray textureList = hud.getAsJsonArray("textures");
            for (int i = 0; i < textureList.size(); i++) {
                JsonObject t = textureList.get(i).getAsJsonObject();
                String file = t.get("file").getAsString();
                if (file.contains("/") || file.contains("\\") || file.contains("..")) {
                    throw new IllegalStateException("invalid marker texture name " + file);
                }
                ResourceLocation location = TrickHud.uploadRaw(folder.resolve(file), t.get("width").getAsInt(),
                        t.get("height").getAsInt(), ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, "marker/" + i));
                if (location == null) {
                    throw new IllegalStateException("missing marker texture " + file);
                }
                textures.add(location);
            }
            List<Primitive> loaded = new ArrayList<>();
            for (JsonElement element : hud.getAsJsonArray("meshes")) {
                JsonObject mesh = element.getAsJsonObject();
                ResourceLocation texture = TrickHud.white();
                if (mesh.has("texture") && !mesh.get("texture").isJsonNull()) {
                    texture = textures.get(mesh.get("texture").getAsInt());
                }
                JsonArray c = mesh.getAsJsonArray("color");
                float[] color = {c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat(),
                        c.get(3).getAsFloat()};
                JsonArray vertices = mesh.getAsJsonArray("vertices");
                float[] data = new float[vertices.size() * 4];
                for (int v = 0; v < vertices.size(); v++) {
                    JsonObject vertex = vertices.get(v).getAsJsonObject();
                    JsonArray p = vertex.getAsJsonArray("position");
                    JsonArray uv = vertex.getAsJsonArray("uv");
                    data[v * 4] = p.get(0).getAsFloat();
                    data[v * 4 + 1] = p.get(1).getAsFloat();
                    data[v * 4 + 2] = uv.get(0).getAsFloat();
                    data[v * 4 + 3] = uv.get(1).getAsFloat();
                }
                loaded.add(new Primitive(texture, color, data));
            }
            primitives = loaded;
            return true;
        } catch (Exception e) {
            failed = true;
            MineSkate3.LOGGER.warn("Session-marker HUD unavailable", e);
            return false;
        }
    }

    public static void render(GuiGraphics graphics) {
        SkateSession session = SkateSession.get();
        if (!session.posePublished() || session.pose()[NativeSkate.STATUS + 4] == 0f || !load()) {
            return;
        }
        if (!TrickHud.begin(graphics)) {
            return;
        }
        // Uniform scale from the 1280x720 canvas, anchored top left, as skate-game does.
        float scale = Math.min(graphics.guiWidth() / 1280f, graphics.guiHeight() / 720f);
        Matrix4f matrix = new Matrix4f(graphics.pose().last().pose()).scale(scale, scale, 1f);
        float[] add = {0f, 0f, 0f, 0f};
        for (Primitive primitive : primitives) {
            TrickHud.triangles(matrix, primitive.texture(), primitive.color(), add, primitive.vertices(), 0,
                    primitive.vertices().length / 4);
        }
        TrickHud.end();
    }
}
