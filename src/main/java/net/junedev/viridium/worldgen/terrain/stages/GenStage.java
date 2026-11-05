package net.junedev.viridium.worldgen.terrain.stages;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Seeds;

/** Base class for pipeline stages: gives access to the generator, its parameters and seeds. Stages are stateless. */
public abstract class GenStage {

    protected final WorldGen gen;
    protected final GenParams P;
    protected final Seeds SD;

    protected GenStage(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.SD = gen.seeds;
    }
}
