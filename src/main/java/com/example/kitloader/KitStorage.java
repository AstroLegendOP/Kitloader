package com.example.kitloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KitStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path getKitDir(ServerPlayer player) {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("kitloader")
                .resolve("kits")
                .resolve(player.getStringUUID());
    }

    /** Saves a player's inventory (main 0-35, armor 36-39, offhand 40) as a named kit. */
    public static boolean saveKit(MinecraftServer server, ServerPlayer player, String name, List<ItemStack> items) {
        Path dir = getKitDir(player);
        try {
            Files.createDirectories(dir);
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            JsonArray array = new JsonArray();
            for (ItemStack stack : items) {
                if (stack.isEmpty()) {
                    array.add(JsonNull.INSTANCE);
                } else {
                    array.add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
                }
            }
            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            root.add("items", array);
            root.addProperty("savedAt", System.currentTimeMillis());
            Files.writeString(dir.resolve(name + ".json"), GSON.toJson(root));
            KitLoaderMod.LOGGER.info("Saved kit \"{}\" for {}", name, player.getName().getString());
            return true;
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to save kit \"{}\"", name, e);
            return false;
        }
    }

    /** Loads a kit as a list of 41 stacks, or null if it does not exist anywhere. */
    public static List<ItemStack> loadKit(MinecraftServer server, ServerPlayer player, String name) {
        Path file;
        try {
            file = findKitFile(name);
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to search for kit \"{}\"", name, e);
            return null;
        }
        if (file == null) {
            return null;
        }
        try {
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null || !root.has("items") || !root.get("items").isJsonArray()) {
                KitLoaderMod.LOGGER.error("Kit \"{}\" is missing the items array", name);
                return null;
            }
            JsonArray array = root.getAsJsonArray("items");
            List<ItemStack> items = new ArrayList<>();
            for (JsonElement elem : array) {
                if (elem.isJsonNull()) {
                    items.add(ItemStack.EMPTY);
                    continue;
                }
                try {
                    items.add(ItemStack.CODEC.decode(ops, elem).getOrThrow().getFirst());
                } catch (Exception e) {
                    // A single unreadable item (e.g. one whose data format
                    // changed between versions) must not invalidate the kit.
                    KitLoaderMod.LOGGER.warn("Skipping unreadable item in kit \"{}\": {}", name, elem, e);
                    items.add(ItemStack.EMPTY);
                }
            }
            return items;
        } catch (Exception e) {
            KitLoaderMod.LOGGER.error("Failed to load kit \"{}\"", name, e);
            return null;
        }
    }

    /** Returns the names of all kits saved for this player across all sessions and versions, sorted. */
    public static List<String> listKits(ServerPlayer player) {
        try {
            return kitFiles().stream()
                    .map(p -> p.getFileName().toString())
                    .map(s -> s.substring(0, s.length() - ".json".length()))
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to list kits for {}", player.getName().getString(), e);
            return List.of();
        }
    }

    /**
     * All kit files under kits/, regardless of player subdirectory. The dev
     * client gives every launch a random username, so the per-player
     * subdirectory changes between launches and versions; searching all of
     * them keeps kits accessible across sessions.
     */
    private static List<Path> kitFiles() throws IOException {
        Path kitsRoot = FabricLoader.getInstance().getConfigDir()
                .resolve("kitloader")
                .resolve("kits");
        if (!Files.isDirectory(kitsRoot)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> playerDirs = Files.list(kitsRoot)) {
            for (Path dir : playerDirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    /** The most recently saved file for a kit name, or null if none exists. */
    private static Path findKitFile(String name) throws IOException {
        Path best = null;
        long bestTime = -1;
        for (Path file : kitFiles()) {
            if (!file.getFileName().toString().equals(name + ".json")) {
                continue;
            }
            long time = Files.getLastModifiedTime(file).toMillis();
            if (time > bestTime) {
                bestTime = time;
                best = file;
            }
        }
        return best;
    }
}
