package net.junedev.viridium;

import net.junedev.viridium.worldgen.terrain.cube.ViridiumCommand;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        Viridium.LOGGER.info(Config.greeting);
        Viridium.LOGGER.info("I am Viridium at version " + Tags.VERSION);

    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        // optional Distant Horizons LODs from the Viridium pipeline (its classes load only when DH is present)
        if (Loader.isModLoaded("distanthorizons")) {
            net.junedev.viridium.compat.dh.DistantHorizonsCompat.init();
        }
    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new ViridiumCommand());
    }

    public void loadComplete(FMLLoadCompleteEvent event) {}
}
