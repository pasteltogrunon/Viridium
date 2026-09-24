package net.junedev.viridium.biomes;

import net.minecraft.world.biome.BiomeGenBase;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public class ViridiumBiomeGen extends BiomeGenBase {

    private Integer grassColorOverride;
    private Integer foliageColorOverride;

    public ViridiumBiomeGen(int id) {
        super(id);
    }

    /** Sets the color displayed for this biome on maps. */
    public ViridiumBiomeGen setMapColor(int color) {
        setColor(color);
        return this;
    }

    /**
     * Sets a fixed RGB color for grass and tall grass. If unset, vanilla's
     * temperature/rainfall color calculation is used.
     */
    public ViridiumBiomeGen setGrassColorOverride(int color) {
        grassColorOverride = color;
        return this;
    }

    /**
     * Sets a fixed RGB color for leaves and vines. If unset, vanilla's
     * temperature/rainfall color calculation is used.
     */
    public ViridiumBiomeGen setFoliageColorOverride(int color) {
        foliageColorOverride = color;
        return this;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getBiomeGrassColor(int p_150558_1_, int p_150558_2_, int p_150558_3_) {
        return grassColorOverride != null
            ? grassColorOverride
            : super.getBiomeGrassColor(p_150558_1_, p_150558_2_, p_150558_3_);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getBiomeFoliageColor(int p_150571_1_, int p_150571_2_, int p_150571_3_) {
        return foliageColorOverride != null
            ? foliageColorOverride
            : super.getBiomeFoliageColor(p_150571_1_, p_150571_2_, p_150571_3_);
    }
}
