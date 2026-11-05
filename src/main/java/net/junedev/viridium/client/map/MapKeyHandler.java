package net.junedev.viridium.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opens the world map with a key (M by default). */
public final class MapKeyHandler {

    private final KeyBinding key = new KeyBinding("key.viridium.map", Keyboard.KEY_M, "key.categories.viridium");

    public static void register() {
        MapKeyHandler h = new MapKeyHandler();
        ClientRegistry.registerKeyBinding(h.key);
        FMLCommonHandler.instance()
            .bus()
            .register(h);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (key.isPressed() && mc.currentScreen == null && mc.thePlayer != null) {
            mc.displayGuiScreen(new WorldMapScreen());
        }
    }
}
