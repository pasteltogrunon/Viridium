package net.junedev.viridium;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String greeting = "Hello World";
    /** The Nether of Viridium worlds uses the Viridium nether generator (otherwise vanilla, through CC). */
    public static boolean viridiumNether = true;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        greeting = configuration.getString("greeting", Configuration.CATEGORY_GENERAL, greeting, "How shall I greet?");
        viridiumNether = configuration.getBoolean(
            "viridiumNether",
            "worldgen",
            viridiumNether,
            "Generate the Nether of Viridium worlds with the Viridium nether generator (experimental)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
