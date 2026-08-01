package com.example.kitloader;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.List;

/**
 * Client-side kit loading. The client cannot push inventory contents to the
 * server in survival mode, so if the player is not in creative the mod
 * temporarily switches to creative (via the /gamemode command), applies the
 * kit, and then restores the previous game mode. If creative is not granted
 * within a short timeout, loading is aborted with an error instead of hanging.
 */
public final class KitClientManager {
    private static final int CREATIVE_TIMEOUT_TICKS = 100; // 5 seconds
    private static final int RESTORE_DELAY_TICKS = 20;     // 1 second

    private static State state = State.IDLE;
    private static GameType originalGameMode;
    private static List<ItemStack> pendingItems;
    private static String pendingName;
    private static FabricClientCommandSource pendingSource;
    private static int timer;

    private KitClientManager() {
    }

    public static void requestLoad(FabricClientCommandSource source, String name, List<ItemStack> items) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (player.isCreative()) {
            apply(items);
            source.sendFeedback(Component.literal("Loaded kit \"" + name + "\"."));
            return;
        }

        pendingItems = items;
        pendingName = name;
        pendingSource = source;
        originalGameMode = mc.gameMode.getPlayerMode();
        state = State.SWITCHING_TO_CREATIVE;
        timer = CREATIVE_TIMEOUT_TICKS;
        source.sendFeedback(Component.literal("Switching to creative to load kit \"" + name + "\"..."));
        player.connection.sendCommand("gamemode creative");
    }

    public static void tick() {
        if (state == State.IDLE) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) {
            reset();
            return;
        }

        switch (state) {
            case SWITCHING_TO_CREATIVE -> {
                if (player.isCreative() && pendingItems != null) {
                    apply(pendingItems);
                    pendingItems = null;
                    if (pendingSource != null && pendingName != null) {
                        pendingSource.sendFeedback(Component.literal("Loaded kit \"" + pendingName + "\"."));
                    }
                    timer = RESTORE_DELAY_TICKS;
                    state = State.RESTORING_GAMEMODE;
                } else if (--timer <= 0) {
                    if (pendingSource != null) {
                        pendingSource.sendError(Component.literal("Could not load kit: creative mode was not granted (need cheats or permissions)."));
                    }
                    reset();
                }
            }
            case RESTORING_GAMEMODE -> {
                if (--timer <= 0) {
                    if (player != null && originalGameMode != null) {
                        player.connection.sendCommand("gamemode " + originalGameMode.getName());
                    }
                    reset();
                }
            }
        }
    }

    private static void apply(List<ItemStack> items) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        inventory.clearContent();
        int slots = inventory.getContainerSize();
        for (int i = 0; i < items.size() && i < slots; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                inventory.setItem(i, stack);
            }
        }
        // Re-sync every container slot to the server the same way creative mode does.
        AbstractContainerMenu menu = player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            mc.gameMode.handleCreativeModeItemAdd(menu.slots.get(i).getItem(), i);
        }
        menu.broadcastChanges();
    }

    private static void reset() {
        state = State.IDLE;
        originalGameMode = null;
        pendingItems = null;
        pendingName = null;
        pendingSource = null;
        timer = 0;
    }

    private enum State {
        IDLE,
        SWITCHING_TO_CREATIVE,
        RESTORING_GAMEMODE
    }
}
