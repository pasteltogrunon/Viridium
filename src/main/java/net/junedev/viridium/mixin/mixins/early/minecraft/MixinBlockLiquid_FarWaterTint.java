package net.junedev.viridium.mixin.mixins.early.minecraft;

import net.junedev.viridium.client.renderers.world.water.WaterTint;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.world.IBlockAccess;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Viridium water colour in Distant Horizons LODs. Our tint is applied in RenderBlocks.renderBlockLiquid, which DH never
 * calls: it asks Block.colorMultiplier through its own FakeWorld (one block and its biome). For that world only, the
 * biome water profile is applied here at a fixed depth (the FakeWorld has no neighbours to measure one).
 */
@Mixin(BlockLiquid.class)
public abstract class MixinBlockLiquid_FarWaterTint {

    private static final String DH_FAKE_WORLD = "com.seibel.distanthorizons.common.backports.FakeWorld";

    @Inject(method = "colorMultiplier", at = @At("RETURN"), cancellable = true)
    private void viridium$farWaterTint(IBlockAccess world, int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        if (world == null || !DH_FAKE_WORLD.equals(world.getClass().getName())) return;
        if (((BlockLiquid) (Object) this).getMaterial() != Material.water) return;
        cir.setReturnValue(WaterTint.applyFar(cir.getReturnValue(), world, x, y, z));
    }
}
