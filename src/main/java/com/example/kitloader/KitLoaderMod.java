package com.example.kitloader;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KitLoaderMod implements ModInitializer {
    public static final String MOD_ID = "kitloader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        KitCommands.register();
        LOGGER.info("KitLoader Mod initialized!");
    }
}
