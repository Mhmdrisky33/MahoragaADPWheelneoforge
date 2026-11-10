package com.mahoraga.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local HUD preferences; stored in config/mahoraga_hud.json. */
public final class WheelHudSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Data DEFAULTS = new Data();
    private static Data data = new Data();
    private static boolean loaded;

    private WheelHudSettings() {}

    public static void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        Path path = path();
        try {
            if (Files.exists(path)) {
                Data loadedData = GSON.fromJson(Files.readString(path), Data.class);
                if (loadedData != null) data = loadedData;
            }
        } catch (Exception ignored) {
            data = new Data();
        }
        clamp();
    }

    public static int x() { loadIfNeeded(); return data.x; }
    public static int y() { loadIfNeeded(); return data.y; }
    public static float scale() { loadIfNeeded(); return data.scale; }
    public static boolean showIcon() { loadIfNeeded(); return data.showIcon; }
    public static boolean showEntries() { loadIfNeeded(); return data.showEntries; }

    public static void moveX(int amount) { loadIfNeeded(); data.x = clamp(data.x + amount, 0, 2000); save(); }
    public static void moveY(int amount) { loadIfNeeded(); data.y = clamp(data.y + amount, 0, 2000); save(); }
    public static void changeScale(float amount) { loadIfNeeded(); data.scale = Math.max(0.5F, Math.min(2.5F, data.scale + amount)); save(); }
    public static void toggleIcon() { loadIfNeeded(); data.showIcon = !data.showIcon; save(); }
    public static void toggleEntries() { loadIfNeeded(); data.showEntries = !data.showEntries; save(); }

    public static void reset() {
        data = new Data();
        loaded = true;
        save();
    }

    public static void save() {
        loadIfNeeded();
        clamp();
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException ignored) {
            // Keep the current session usable even if the config directory is read-only.
        }
    }

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("mahoraga_hud.json");
    }

    private static void clamp() {
        data.x = clamp(data.x, 0, 2000);
        data.y = clamp(data.y, 0, 2000);
        data.scale = Math.max(0.5F, Math.min(2.5F, data.scale));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Data {
        int x = 5;
        int y = 5;
        float scale = 1.0F;
        boolean showIcon = true;
        boolean showEntries = true;
    }
}
