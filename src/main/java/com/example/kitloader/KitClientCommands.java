package com.example.kitloader;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side /kit command. Lets a client-only install of the mod save and
 * load kits on servers that do not have the mod. The command is only
 * registered when connected to a multiplayer server; in singleplayer the
 * integrated server registers /kit itself and the server-side path (which
 * can load kits without creative mode) handles the command.
 */
public class KitClientCommands {
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // In singleplayer the integrated server already registers /kit via
            // the server-side entrypoint. Registering the client command here
            // too would shadow the server command, and Fabric's client command
            // API re-routes any sendCommand() call for a client-registered name
            // back through the client dispatcher, so forwarding to the server
            // from a client handler recurses forever (StackOverflowError).
            // Skip registration when connected to an integrated server.
            if (Minecraft.getInstance().getSingleplayerServer() != null) {
                return;
            }
            dispatcher.register(literal("kit")
                    .then(literal("save")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> saveKit(ctx, StringArgumentType.getString(ctx, "name")))))
                    .then(literal("load")
                            .executes(KitClientCommands::listKits)
                            .then(argument("name", StringArgumentType.word())
                                    .suggests(KitClientCommands::suggestKits)
                                    .executes(ctx -> loadKit(ctx, StringArgumentType.getString(ctx, "name")))))
                    .then(literal("preview")
                            .then(argument("name", StringArgumentType.word())
                                    .suggests(KitClientCommands::suggestKits)
                                    .executes(ctx -> previewKit(ctx, StringArgumentType.getString(ctx, "name")))))
                    .then(literal("delete")
                            .then(argument("name", StringArgumentType.word())
                                    .suggests(KitClientCommands::suggestKits)
                                    .executes(ctx -> deleteKit(ctx, StringArgumentType.getString(ctx, "name"))))));
        });
    }

    /**
     * The Fabric client command helper (ClientCommands / ClientCommandManager)
     * changed its class name between supported Minecraft versions, so build the
     * tree with plain Brigadier instead, pinning the source type to
     * FabricClientCommandSource. Brigadier ships with every Minecraft version.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private static int saveKit(CommandContext<FabricClientCommandSource> ctx, String name) {
        LocalPlayer player = player(ctx);
        if (player == null) {
            return 0;
        }
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            items.add(player.getInventory().getItem(i).copy());
        }
        if (KitStorage.saveClientKit(player.registryAccess(), name, items)) {
            feedback(ctx, "Saved kit \"" + name + "\" (client).");
            return 1;
        }
        error(ctx, "Failed to save kit \"" + name + "\".");
        return 0;
    }

    private static int loadKit(CommandContext<FabricClientCommandSource> ctx, String name) {
        LocalPlayer player = player(ctx);
        if (player == null) {
            return 0;
        }
        List<ItemStack> items = KitStorage.loadClientKit(player.registryAccess(), name);
        if (items == null) {
            error(ctx, "Kit \"" + name + "\" could not be loaded (missing or corrupt). Press Tab after /kit load to see your saved kits.");
            return 0;
        }
        KitClientManager.requestLoad(ctx.getSource(), name, items);
        return 1;
    }

    private static int previewKit(CommandContext<FabricClientCommandSource> ctx, String name) {
        LocalPlayer player = player(ctx);
        if (player == null) {
            return 0;
        }
        List<ItemStack> items = KitStorage.loadClientKit(player.registryAccess(), name);
        if (items == null) {
            error(ctx, "Kit \"" + name + "\" could not be loaded (missing or corrupt). Press Tab after /kit preview to see your saved kits.");
            return 0;
        }
        String desc = KitStorage.describeKit(items);
        if (desc.isEmpty()) {
            feedback(ctx, "Kit \"" + name + "\" is empty.");
        } else {
            feedback(ctx, "Preview of \"" + name + "\": " + desc);
        }
        return 1;
    }

    private static int deleteKit(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (KitStorage.deleteClientKit(name)) {
            feedback(ctx, "Deleted kit \"" + name + "\".");
            return 1;
        }
        error(ctx, "No kit named \"" + name + "\" found.");
        return 0;
    }

    private static int listKits(CommandContext<FabricClientCommandSource> ctx) {
        List<String> kits = KitStorage.listAllClientKits();
        if (kits.isEmpty()) {
            feedback(ctx, "You have no saved kits. Use /kit save <name> to save one.");
        } else {
            feedback(ctx, "Saved kits: " + String.join(", ", kits));
        }
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestKits(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        for (String kit : KitStorage.listAllClientKits()) {
            builder.suggest(kit);
        }
        return builder.buildFuture();
    }

    private static LocalPlayer player(CommandContext<FabricClientCommandSource> ctx) {
        LocalPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendError(Component.literal("Not connected to a server."));
        }
        return player;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendFeedback(Component.literal(message));
    }

    private static void error(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendError(Component.literal(message));
    }
}
