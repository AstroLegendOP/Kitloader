package com.example.kitloader;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class KitCommands {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("kit")
                    .then(Commands.literal("save")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .executes(ctx -> saveKit(ctx, StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("load")
                            .executes(KitCommands::listKits)
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests(KitCommands::suggestKits)
                                    .executes(ctx -> loadKit(ctx, StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("preview")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests(KitCommands::suggestKits)
                                    .executes(ctx -> previewKit(ctx, StringArgumentType.getString(ctx, "name")))))
                    .then(Commands.literal("delete")
                            .then(Commands.argument("name", StringArgumentType.word())
                                    .suggests(KitCommands::suggestKits)
                                    .executes(ctx -> deleteKit(ctx, StringArgumentType.getString(ctx, "name"))))));
        });
    }

    private static int saveKit(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player."));
            return 0;
        }
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            items.add(player.getInventory().getItem(i).copy());
        }
        boolean saved = KitStorage.saveKit(source.getServer(), player, name, items);
        if (!saved) {
            source.sendFailure(Component.literal("Failed to save kit \"" + name + "\". Check the server log."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Saved kit \"" + name + "\"."), false);
        return 1;
    }

    private static int listKits(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player."));
            return 0;
        }
        List<String> kits = KitStorage.listKits(player);
        if (kits.isEmpty()) {
            source.sendSuccess(() -> Component.literal("You have no saved kits. Use /kit save <name> to save one."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Saved kits: " + String.join(", ", kits)), false);
        return 1;
    }

    /** Suggests the saved kit names while typing "/kit load " (like /gamemode suggests its modes). */
    private static CompletableFuture<Suggestions> suggestKits(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            for (String kit : KitStorage.listKits(player)) {
                builder.suggest(kit);
            }
        }
        return builder.buildFuture();
    }

    private static int loadKit(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player."));
            return 0;
        }
        List<ItemStack> items = KitStorage.loadKit(source.getServer(), player, name);
        if (items == null) {
            source.sendFailure(Component.literal("Kit \"" + name + "\" could not be loaded (missing or corrupt). Press Tab after /kit load to see your saved kits."));
            return 0;
        }
        // Completely override the inventory: clear every slot (main, armor,
        // offhand) first, then restore each item to its exact original slot.
        player.getInventory().clearContent();
        int slots = player.getInventory().getContainerSize();
        for (int i = 0; i < items.size() && i < slots; i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                player.getInventory().setItem(i, stack);
            }
        }
        // A kit with more items than the inventory can hold is placed back,
        // and any overflow is dropped at the player by the inventory itself.
        for (int i = slots; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                player.getInventory().placeItemBackInInventory(stack);
            }
        }
        source.sendSuccess(() -> Component.literal("Loaded kit \"" + name + "\"."), false);
        return 1;
    }

    private static int previewKit(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player."));
            return 0;
        }
        List<ItemStack> items = KitStorage.loadKit(source.getServer(), player, name);
        if (items == null) {
            source.sendFailure(Component.literal("Kit \"" + name + "\" could not be loaded (missing or corrupt). Press Tab after /kit preview to see your saved kits."));
            return 0;
        }
        // Open a read-only chest screen with the kit's items. The menu holds a
        // snapshot, so the saved kit can never be modified from the preview.
        List<ItemStack> snapshot = new ArrayList<>(items);
        player.openMenu(new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal("Kit: " + name);
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player owner) {
                return new KitPreviewMenu(containerId, inventory, snapshot);
            }
        });
        source.sendSuccess(() -> Component.literal("Previewing kit \"" + name + "\"."), false);
        return 1;
    }

    private static int deleteKit(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be used by a player."));
            return 0;
        }
        boolean deleted = KitStorage.deleteKit(name);
        if (!deleted) {
            source.sendFailure(Component.literal("No kit named \"" + name + "\" found."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Deleted kit \"" + name + "\"."), false);
        return 1;
    }

}
