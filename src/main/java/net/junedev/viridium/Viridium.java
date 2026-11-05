package net.junedev.viridium;

import net.junedev.viridium.worldgen.terrain.cube.ViridiumWorldType;
import net.junedev.viridium.worldgen.treegen.TreeRegistry;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = Viridium.MOD_ID,
    version = Tags.VERSION,
    name = "Viridium",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:angelica")
public class Viridium {

    public static final String MOD_ID = "viridium";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final ViriTab VTab = new ViriTab("VTab");

    @SidedProxy(clientSide = "net.junedev.viridium.ClientProxy", serverSide = "net.junedev.viridium.CommonProxy")
    public static CommonProxy proxy;
    ViriBlocks blocks;
    ViriItems items;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {

        MinecraftForge.EVENT_BUS.register(this);

        blocks = new ViriBlocks();
        blocks.preInit();

        items = new ViriItems();
        items.preInit();

        TreeRegistry.preInit();
        ViridiumWorldType.init();

        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(cpw.mods.fml.common.event.FMLServerStartedEvent event) {
        // dev aid: VIRIDIUM_BENCH_ON_START=1 runs the generator benchmark around the spawn and logs it
        if ("1".equals(System.getenv("VIRIDIUM_BENCH_ON_START"))) {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            net.junedev.viridium.worldgen.terrain.cube.ViridiumCommand cmd = new net.junedev.viridium.worldgen.terrain.cube.ViridiumCommand();
            cmd.processCommand(server, new String[] { "bench", "6", "14", "6" }); // cold (JIT, caches)
            cmd.processCommand(server, new String[] { "bench", "6", "14", "6" }); // warm
        }
    }

    @Mod.EventHandler
    // loadComplete "All mods are loaded, all registries are final." Used to check things that depend on other mods'
    // content.
    public void loadComplete(FMLLoadCompleteEvent event) {
        proxy.loadComplete(event);
    }

}
