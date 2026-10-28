package net.junedev.viridium.compat.nei;

import net.junedev.viridium.Tags;
import net.junedev.viridium.ViriBlocks;
import net.junedev.viridium.Viridium;
import net.minecraft.item.ItemStack;

import codechicken.nei.api.API;
import codechicken.nei.api.IConfigureNEI;
import codechicken.nei.event.NEIRegisterHandlerInfosEvent;
import codechicken.nei.recipe.HandlerInfo;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** Discovered by NEI only on clients where NEI is installed. */
public final class NEIViridiumConfig implements IConfigureNEI {

    @Override
    public void loadConfig() {
        TreePreviewCatalog.initialize();
        API.registerRecipeHandler(new TreePreviewHandler());
        API.registerUsageHandler(new TreePreviewHandler());
    }

    @SubscribeEvent
    public void registerHandlerInfo(NEIRegisterHandlerInfosEvent event) {
        event.registerHandlerInfo(
            new HandlerInfo.Builder(TreePreviewHandler.class, "Viridium", Viridium.MOD_ID)
                .setDisplayStack(new ItemStack(ViriBlocks.pedunculate_oak.sapling))
                .setWidth(166)
                .setHeight(TreePreviewHandler.HEIGHT)
                .setMaxRecipesPerPage(1)
                .setShowOverlayButton(false)
                .setShowFavoritesButton(false)
                .build());
    }

    @Override
    public String getName() {
        return "Viridium tree previews";
    }

    @Override
    public String getVersion() {
        return Tags.VERSION;
    }
}
