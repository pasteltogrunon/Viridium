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
            cmd.processCommand(server, new String[] { "stats", "reset" }); // what the spawn area cost
            cmd.processCommand(server, new String[] { "bench", "6", "14", "6" }); // cold (JIT, caches)
            cmd.processCommand(server, new String[] { "bench", "6", "14", "6" }); // warm
        }
        // dev aid: VIRIDIUM_GENBENCH="radius,cyMin,cyMax[;...]" pregenerates boxes through CC (any CC world type,
        // Viridium or VanillaCubic), appends the report to genbench_result.txt and stops the server
        String gb = System.getenv("VIRIDIUM_GENBENCH");
        if (gb != null && !gb.isEmpty()) {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            net.minecraft.world.WorldServer w = server.worldServerForDimension(0);
            StringBuilder all = new StringBuilder();
            int n = 0;
            for (String spec : gb.split(";")) {
                String[] a = spec.trim()
                    .split(",");
                int r = Integer.parseInt(a[0]), lo = Integer.parseInt(a[1]), hi = Integer.parseInt(a[2]);
                // warm-up (JIT, class loading) far from the measured box, then the measured box on fresh ground
                net.junedev.viridium.worldgen.terrain.cube.GenBenchmark.run(w, 3, lo, hi, -40000 - 5000 * n, -40000);
                String rep = net.junedev.viridium.worldgen.terrain.cube.GenBenchmark
                    .run(w, r, lo, hi, 40000 + 5000 * n, 40000);
                LOGGER.info("Generation benchmark:\n{}", rep);
                all.append(rep)
                    .append('\n');
                n++;
            }
            try {
                java.nio.file.Files.write(
                    new java.io.File("genbench_result.txt").toPath(),
                    all.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException e) {
                LOGGER.warn("could not write genbench_result.txt", e);
            }
            server.initiateShutdown();
        }
    }

    @Mod.EventHandler
    // loadComplete "All mods are loaded, all registries are final." Used to check things that depend on other mods'
    // content.
    public void loadComplete(FMLLoadCompleteEvent event) {
        proxy.loadComplete(event);
    }

}
