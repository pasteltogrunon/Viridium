package net.junedev.viridium.compat.dh;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.cube.ViridiumWorldType;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;

/**
 * Distant Horizons integration. DH cannot generate Cubic Chunks worlds on its own (its 1.7.10 chunk generator expects
 * vanilla columns), so for every Viridium world it is handed {@link ViridiumLodGenerator}, which builds LOD columns
 * straight from the 2D terrain pipeline without generating a single cube.
 * <p>
 * Only reached when DH is loaded; nothing else in the mod references these classes.
 */
public final class DistantHorizonsCompat {

    private DistantHorizonsCompat() {}

    public static void init() {
        DhApi.events.bind(DhApiLevelLoadEvent.class, new LevelLoadHandler());
        Viridium.LOGGER.info(
            "Distant Horizons detected: Viridium worlds will provide their own LODs (DH API {}.{}.{})",
            DhApi.getApiMajorVersion(),
            DhApi.getApiMinorVersion(),
            DhApi.getApiPatchVersion());
    }

    /** True for worlds whose terrain comes from the Viridium generator. */
    static boolean isViridium(World world) {
        return world.provider.dimensionId == 0 && ViridiumWorldType.INSTANCE != null
            && world.getWorldInfo()
                .getTerrainType() == ViridiumWorldType.INSTANCE;
    }

    public static final class LevelLoadHandler extends DhApiLevelLoadEvent {

        @Override
        public void onLevelLoad(DhApiEventParam<EventParam> input) {
            IDhApiLevelWrapper level = input.value.levelWrapper;
            // only server levels generate; a client level without a server has nothing to generate from
            if (level.getLevelType() != EDhApiLevelType.SERVER_LEVEL) return;
            Object mc = level.getWrappedMcObject();
            if (!(mc instanceof WorldServer world) || !isViridium(world)) return;

            DhApiResult<Void> r = DhApi.worldGenOverrides
                .registerWorldGeneratorOverride(level, new ViridiumLodGenerator(world, level));
            if (r.success) Viridium.LOGGER.info("Viridium LOD generator registered for {}", level.getDimensionName());
            else Viridium.LOGGER.warn("Could not register the Viridium LOD generator: {}", r.message);
        }
    }
}
