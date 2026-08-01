package com.example.kitloader;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class KitLoaderClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KitClientCommands.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.gameMode != null) {
                KitClientManager.tick();
            }
        });
        KitLoaderMod.LOGGER.info("KitLoader client initialized!");
    }
}
