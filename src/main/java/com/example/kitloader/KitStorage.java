package com.example.kitloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
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

    private static Path kitsRoot() {
        return FabricLoader.getInstance().getConfigDir().resolve("kitloader").resolve("kits");
    }

    private static Path getKitDir(ServerPlayer player) {
        return kitsRoot().resolve(player.getStringUUID());
    }

    /** Kits saved by the client-side command on a server that does not have the mod installed. */
    private static Path clientKitsDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("kitloader").resolve("client-kits");
    }

    /** Saves a player's inventory (main 0-35, armor 36-39, offhand 40) as a named kit. */
    public static boolean saveKit(MinecraftServer server, ServerPlayer player, String name, List<ItemStack> items) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return saveTo(getKitDir(player), ops, name, items);
    }

    /** Saves a kit from the client into the shared client-side kit folder. */
    public static boolean saveClientKit(RegistryAccess registryAccess, String name, List<ItemStack> items) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
        return saveTo(clientKitsDir(), ops, name, items);
    }

    private static boolean saveTo(Path dir, RegistryOps<JsonElement> ops, String name, List<ItemStack> items) {
        try {
            Files.createDirectories(dir);
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
            KitLoaderMod.LOGGER.info("Saved kit \"{}\"", name);
            return true;
        } catch (Exception e) {
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
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        return readKit(file, ops, name);
    }

    /**
     * Client-side load: reads the kit from the shared client folder, falling
     * back to the per-player server folders written by the server-side mod.
     */
    public static List<ItemStack> loadClientKit(RegistryAccess registryAccess, String name) {
        Path file = clientKitFile(name);
        if (file == null) {
            try {
                file = findKitFile(name);
            } catch (IOException e) {
                KitLoaderMod.LOGGER.error("Failed to search for kit \"{}\"", name, e);
                return null;
            }
        }
        if (file == null) {
            return null;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
        return readKit(file, ops, name);
    }

    /** Formats a kit for a chat preview, e.g. "Diamond Sword x1, Iron Chestplate x1". Empty kit -> "". */
    public static String describeKit(List<ItemStack> items) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                parts.add(stack.getHoverName().getString() + " x" + stack.getCount());
            }
        }
        return String.join(", ", parts);
    }

    private static List<ItemStack> readKit(Path file, RegistryOps<JsonElement> ops, String name) {
        try {
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
            return listNamesInDirs(kitFiles());
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to list kits for {}", player.getName().getString(), e);
            return List.of();
        }
    }

    /** All kit names visible to the client: client-side kits plus any server per-player kits on this machine. */
    public static List<String> listAllClientKits() {
        List<Path> files = new ArrayList<>();
        Path dir = clientKitsDir();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
            } catch (IOException e) {
                KitLoaderMod.LOGGER.error("Failed to list client kits", e);
            }
        }
        try {
            files.addAll(kitFiles());
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to list server kits", e);
        }
        return listNamesInDirs(files);
    }

    private static List<String> listNamesInDirs(List<Path> files) {
        return files.stream()
                .map(p -> p.getFileName().toString())
                .map(s -> s.substring(0, s.length() - ".json".length()))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * All kit files under kits/, regardless of player subdirectory. The dev
     * client gives every launch a random username, so the per-player
     * subdirectory changes between launches and versions; searching all of
     * them keeps kits accessible across sessions.
     */
    private static List<Path> kitFiles() throws IOException {
        Path root = kitsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> playerDirs = Files.list(root)) {
            for (Path dir : playerDirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    /** Deletes every saved file for a kit name, returning true if at least one was removed. */
    public static boolean deleteKit(String name) {
        try {
            return deleteFromDir(kitFiles(), name);
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to delete kit \"{}\"", name, e);
            return false;
        }
    }

    /** Client-side delete: removes the kit from the shared client folder and any server per-player folders. */
    public static boolean deleteClientKit(String name) {
        boolean any = false;
        Path dir = clientKitsDir();
        if (Files.isDirectory(dir)) {
            try {
                if (Files.deleteIfExists(dir.resolve(name + ".json"))) {
                    any = true;
                }
            } catch (IOException e) {
                KitLoaderMod.LOGGER.error("Failed to delete kit \"{}\"", name, e);
                return false;
            }
        }
        try {
            any |= deleteFromDir(kitFiles(), name);
        } catch (IOException e) {
            KitLoaderMod.LOGGER.error("Failed to delete kit \"{}\"", name, e);
            return false;
        }
        return any;
    }

    private static boolean deleteFromDir(List<Path> files, String name) throws IOException {
        boolean any = false;
        for (Path file : files) {
            if (file.getFileName().toString().equals(name + ".json")) {
                Files.deleteIfExists(file);
                any = true;
            }
        }
        return any;
    }

    private static Path clientKitFile(String name) {
        Path dir = clientKitsDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path file = dir.resolve(name + ".json");
        return Files.exists(file) ? file : null;
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
